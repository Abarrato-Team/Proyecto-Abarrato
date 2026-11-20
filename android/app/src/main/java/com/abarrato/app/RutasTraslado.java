package com.abarrato.app;

import android.location.Location;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// Distancias y tiempos por calles (ida y vuelta) usando el servicio OSRM de OpenStreetMap.
final class RutasTraslado {
    private static final String SERVIDOR = "https://routing.openstreetmap.de";
    private static final int DESTINOS_POR_TABLA = 20;
    private static long siguientePeticion;

    private RutasTraslado() {}

    // Calcula las rutas a todos los destinos, en bloques de 20.
    static Map<String, Traslado.Ruta> calcular(Location origen, List<NearbyStores.Result> destinos, boolean aPie)
            throws InterruptedException {
        Map<String, Traslado.Ruta> rutas = new HashMap<>();
        for (int inicio = 0; inicio < destinos.size(); inicio += DESTINOS_POR_TABLA) {
            List<NearbyStores.Result> bloque = destinos.subList(inicio,
                    Math.min(destinos.size(), inicio + DESTINOS_POR_TABLA));
            try {
                consultarBloque(origen, bloque, aPie, rutas);
            } catch (IOException | JSONException error) {
                android.util.Log.w("Abarrato", "Rutas por calles no disponibles: " + error.getClass().getSimpleName());
                break;
            }
        }
        return rutas;
    }

    // Pide a OSRM la tabla de distancias de un bloque de destinos.
    private static void consultarBloque(Location origen, List<NearbyStores.Result> destinos, boolean aPie,
            Map<String, Traslado.Ruta> rutas) throws IOException, JSONException, InterruptedException {
        // Privacidad (OWASP Mobile M6): el origen va redondeado a unos 100 m, igual que en NearbyStores.
        // OSRM solo acepta GET, así que las coordenadas van en la URL; por eso se envía lo mínimo.
        StringBuilder puntos = new StringBuilder(coordenada(NearbyStores.redondear(origen.getLongitude()),
                NearbyStores.redondear(origen.getLatitude())));
        for (NearbyStores.Result resultado : destinos) {
            puntos.append(';').append(coordenada(resultado.store.longitude, resultado.store.latitude));
        }
        String perfil = aPie ? "/routed-foot/table/v1/foot/" : "/routed-car/table/v1/driving/";
        reservarTurno();
        JSONObject tabla = obtener(SERVIDOR + perfil + puntos + "?annotations=duration,distance&generate_hints=false");
        if (!"Ok".equals(tabla.optString("code"))) {
            throw new IOException("Tabla de rutas incompleta");
        }
        JSONArray distancias = tabla.getJSONArray("distances");
        JSONArray duraciones = tabla.getJSONArray("durations");
        for (int i = 0; i < destinos.size(); i++) {
            int indice = i + 1;
            double metros = idaVuelta(distancias, indice);
            double segundos = idaVuelta(duraciones, indice);
            if (Double.isFinite(metros) && Double.isFinite(segundos) && metros >= 0 && segundos >= 0) {
                rutas.put(destinos.get(i).store.id, new Traslado.Ruta(metros, segundos));
            }
        }
    }

    // Suma ida y vuelta de la tabla de OSRM.
    private static double idaVuelta(JSONArray tabla, int indice) throws JSONException {
        return tabla.getJSONArray(0).optDouble(indice, Double.NaN)
                + tabla.getJSONArray(indice).optDouble(0, Double.NaN);
    }

    // Formato "longitud,latitud" que pide OSRM.
    private static String coordenada(double longitud, double latitud) {
        return String.format(Locale.US, "%.4f,%.4f", longitud, latitud);
    }

    // Máximo una petición por segundo, como pide el servicio gratuito.
    private static synchronized void reservarTurno() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
        long pausa = siguientePeticion - SystemClock.elapsedRealtime();
        if (pausa > 0) {
            Thread.sleep(pausa);
        }
        siguientePeticion = SystemClock.elapsedRealtime() + 1000;
    }

    // Hace la petición GET con tiempo máximo y tamaño máximo de respuesta.
    private static JSONObject obtener(String url) throws IOException, JSONException {
        HttpURLConnection conexion = (HttpURLConnection) new URL(url).openConnection();
        conexion.setConnectTimeout(6000);
        conexion.setReadTimeout(8000);
        conexion.setInstanceFollowRedirects(false);
        conexion.setRequestProperty("User-Agent", NearbyStores.USER_AGENT);
        try {
            if (conexion.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("El servicio de rutas no respondió");
            }
            try (InputStream entrada = conexion.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int n;
                while ((n = entrada.read(buffer)) != -1) {
                    if (bytes.size() + n > 512 * 1024) {
                        throw new IOException("Tabla de rutas demasiado grande");
                    }
                    bytes.write(buffer, 0, n);
                }
                return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally {
            conexion.disconnect();
        }
    }
}
