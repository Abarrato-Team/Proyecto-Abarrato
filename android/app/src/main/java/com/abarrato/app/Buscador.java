package com.abarrato.app;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Ejecuta las búsquedas en vivo fuera del hilo principal y entrega solo la respuesta más reciente. */
final class Buscador {
    // Lo que recibe la pantalla: resultados previos (guardados), finales (en vivo) o un error.
    interface Respuesta {
        /** Respuesta rápida con precios guardados, antes de que llegue la búsqueda en vivo. */
        default void previo(ApiPrecios.Busqueda busqueda) {}

        void listo(ApiPrecios.Busqueda busqueda);

        void error(String mensaje);
    }

    private final ExecutorService trabajo = Executors.newSingleThreadExecutor();
    /** Hilo aparte para la respuesta rápida, así no espera a la búsqueda en vivo. */
    private final ExecutorService rapido = Executors.newSingleThreadExecutor();
    private final Handler principal = new Handler(Looper.getMainLooper());
    private int version;

    /*
     * Caché LRU de búsquedas (máx. 40 consultas, válidas 2 minutos): volver a una categoría o
     * subcategoría ya vista responde al instante sin esperar al servidor.
     */
    private static final long VIGENCIA_MS = 2 * 60 * 1000L;
    private static final int MAX_CACHE = 40;
    private static final java.util.Map<String, Guardada> CACHE =
            new java.util.LinkedHashMap<String, Guardada>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Guardada> mayor) {
                    return size() > MAX_CACHE;
                }
            };

    // Búsqueda en caché con la hora en que se hizo.
    private static final class Guardada {
        final ApiPrecios.Busqueda busqueda;
        final long hora;

        Guardada(ApiPrecios.Busqueda busqueda, long hora) {
            this.busqueda = busqueda;
            this.hora = hora;
        }
    }

    // Clave de caché: texto buscado + cantidad de páginas.
    private static String llave(String consulta, int paginas) {
        return Producto.normalizar(consulta).trim() + "#" + paginas;
    }

    // Devuelve una búsqueda de la caché si todavía es reciente.
    private static ApiPrecios.Busqueda guardada(String llave) {
        synchronized (CACHE) {
            Guardada g = CACHE.get(llave);
            if (g == null) {
                return null;
            }
            if (System.currentTimeMillis() - g.hora > VIGENCIA_MS) {
                CACHE.remove(llave);
                return null;
            }
            return g.busqueda;
        }
    }

    // Busca una página de resultados.
    void buscar(String consulta, Respuesta respuesta) {
        buscar(consulta, 1, respuesta);
    }

    // Método B: pide en paralelo los precios guardados (rápido) y los en vivo; entrega los dos en orden.
    void buscar(String consulta, int paginas, Respuesta respuesta) {
        int esta = ++version;
        if (!ApiPrecios.configurada()) {
            respuesta.error("Falta configurar la dirección del servidor de precios de Abarrato.");
            return;
        }
        String llave = llave(consulta, paginas);
        ApiPrecios.Busqueda enCache = guardada(llave);
        if (enCache != null) {
            principal.post(() -> {
                if (esta == version) {
                    respuesta.listo(enCache);
                }
            });
            return;
        }
        // true en cuanto se entregó la búsqueda en vivo: desde ahí la respuesta rápida ya no sirve.
        boolean[] enVivoEntregada = {false};
        if (paginas == 1) {
            rapido.execute(() -> {
                try {
                    ApiPrecios.Busqueda guardados = ApiPrecios.buscarGuardados(consulta);
                    if (guardados.productos != null && !guardados.productos.isEmpty()) {
                        principal.post(() -> {
                            if (esta == version && !enVivoEntregada[0]) {
                                respuesta.previo(guardados);
                            }
                        });
                    }
                } catch (IOException | JSONException error) {
                    // Sin respuesta rápida: se espera la búsqueda en vivo como antes.
                }
            });
        }
        trabajo.execute(() -> {
            Consumer<Runnable> entregar = accion -> principal.post(() -> {
                if (esta == version) {
                    enVivoEntregada[0] = true;
                    accion.run();
                }
            });
            try {
                ApiPrecios.Busqueda busqueda = ApiPrecios.buscarProductos(consulta, paginas);
                if (busqueda.productos != null && !busqueda.productos.isEmpty()) {
                    synchronized (CACHE) {
                        CACHE.put(llave, new Guardada(busqueda, System.currentTimeMillis()));
                    }
                }
                entregar.accept(() -> respuesta.listo(busqueda));
            } catch (ErrorApi error) {
                // El servidor respondió y explicó el problema: se muestra su mensaje tal cual.
                entregar.accept(() -> respuesta.error(error.getMessage()));
            } catch (IOException | JSONException error) {
                entregar.accept(() -> respuesta.error(
                        "No se pudo conectar con el servidor de precios. Revisa tu conexión y vuelve a buscar."));
            }
        });
    }

    // Ignora las respuestas de búsquedas anteriores.
    void cancelar() {
        version++;
    }

    // Detiene las hebras al cerrar la pantalla.
    void cerrar() {
        version++;
        trabajo.shutdownNow();
        rapido.shutdownNow();
    }
}
