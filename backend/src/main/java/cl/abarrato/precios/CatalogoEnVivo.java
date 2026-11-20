package cl.abarrato.precios;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

// Corazón de la búsqueda: consulta las 8 tiendas en paralelo, agrupa los productos iguales,
// usa precios guardados como respaldo y guarda lo leído en segundo plano.
@Service
final class CatalogoEnVivo {
    private static final Logger LOG = LoggerFactory.getLogger(CatalogoEnVivo.class);
    private static final Duration VIGENCIA = Duration.ofMinutes(2);
    private static final Duration REINTENTO = Duration.ofSeconds(45);
    private static final int MAX_BUSQUEDAS_GUARDADAS = 160;
    static final int MAX_PAGINAS = 6;
    private static final List<Comercio> COMERCIOS = List.of(Comercio.values());
    /** Productos que la tarea diaria busca en cada tienda para tener catálogo e historial de precios. */
    private static final List<String> BUSQUEDAS_PROGRAMADAS = List.of(
            "leche", "arroz", "aceite", "huevos", "fideos", "spaghetti", "azucar", "harina", "pan", "pollo",
            "carne", "atun", "porotos", "lentejas", "cafe", "te", "yogur", "queso", "mantequilla", "margarina",
            "detergente", "papel higienico", "jabon", "shampoo", "pasta dental", "tomate", "papas", "cebolla",
            "manzana", "platano", "cereal", "avena", "jugo", "bebida", "agua mineral", "galletas",
            "salsa de tomate", "mayonesa", "sal", "cloro", "suavizante", "pañales", "jamon", "salchichas",
            "mermelada", "chocolate", "cerveza", "vino", "comida para perro", "comida para gato");

    /**
     * Precio de una tienda para un producto. {@code precioAntesClp}: precio normal tachado si hay rebaja;
     * {@code precioOfertaClp} y {@code condicionOferta}: precio especial con condición (tarjeta, cantidad);
     * {@code precioAyerClp}: último precio leído antes de hoy, para saber si bajó o subió.
     */
    record Oferta(Comercio comercio, String productoId, int precioClp, String urlFuente,
                  OffsetDateTime observadoEn, boolean vigente, Integer precioAntesClp,
                  Integer precioOfertaClp, String condicionOferta, Integer precioAyerClp) {
        Oferta(Comercio comercio, String productoId, int precioClp, String urlFuente,
               OffsetDateTime observadoEn, boolean vigente) {
            this(comercio, productoId, precioClp, urlFuente, observadoEn, vigente, null, null, null, null);
        }

        Oferta conPrecioAyer(Integer precio) {
            return new Oferta(comercio, productoId, precioClp, urlFuente, observadoEn, vigente, precioAntesClp,
                    precioOfertaClp, condicionOferta, precio);
        }
    }

    // Producto agrupado: un nombre con las ofertas de cada tienda.
    record Producto(String nombre, List<Oferta> ofertas, String claveCarrito, String urlImagen) {}

    // Estado de cada tienda en la respuesta: RECIENTE, ULTIMA_CAPTURA o NO_DISPONIBLE.
    record Fuente(Comercio comercio, String estado, OffsetDateTime observadoEn) {}

    /** {@code paginas}: páginas del buscador incluidas; {@code hayMas}: si la app puede pedir la siguiente. */
    record Consulta(List<Producto> productos, List<Fuente> fuentes, int paginas, boolean hayMas) {
        Consulta(List<Producto> productos, List<Fuente> fuentes) {
            this(productos, fuentes, 1, false);
        }
    }

    // Resultado interno de leer una página: estado, precios y cuándo volver a leerla.
    private record Captura(Fuente fuente, List<ObservacionPrecio> precios, Instant renovarEn) {}

    private record Coincidencia(ObservacionPrecio precio, boolean vigente) {}
    private record Busqueda(Comercio comercio, String url) {}

    private final CapturadorPrecios capturador;
    private final ServicioPrecios servicio;
    private final ExploradorCatalogos explorador;
    private final Map<Comercio, Captura> cache = new ConcurrentHashMap<>();
    /** Hebra para guardar en la base de datos sin hacer esperar a las búsquedas. */
    private final ExecutorService guardado = Executors.newSingleThreadExecutor();
    private CompletableFuture<Void> guardando = CompletableFuture.completedFuture(null);
    /** Consultas a la base de datos que corren en paralelo con la descarga de las tiendas. */
    private final ExecutorService consultasBase = Executors.newFixedThreadPool(2);
    private final Map<Busqueda, CompletableFuture<Captura>> busquedas = new LinkedHashMap<>(16, 0.75f, true);
    // En servidores con poca CPU (como el plan gratis de Render) conviene descargar menos páginas a la vez.
    private final ExecutorService descargas = Executors.newFixedThreadPool(
            entero("ABARRATO_DESCARGAS", Comercio.values().length, 1, 16));

    CatalogoEnVivo(CapturadorPrecios capturador, ServicioPrecios servicio, ExploradorCatalogos explorador) {
        this.capturador = capturador;
        this.servicio = servicio;
        this.explorador = explorador;
    }

    // Búsqueda en vivo en todas las tiendas (o en una sola si hay filtro).
    Consulta consultar(String consulta, Comercio filtro) {
        return consultar(consulta, filtro == null ? COMERCIOS : List.of(filtro));
    }

    /** Búsqueda en vivo limitada a las cadenas indicadas. */
    Consulta consultar(String consulta, List<Comercio> comercios) {
        return consultar(consulta, comercios, 1);
    }

    /**
     * Búsqueda en vivo de las primeras {@code paginas} páginas de cada buscador. Las páginas ya leídas
     * se reutilizan durante dos minutos, así que pedir la página 2 solo descarga esa página nueva.
     */
    Consulta consultar(String consulta, List<Comercio> comercios, int paginas) {
        int hasta = Math.max(1, Math.min(MAX_PAGINAS, paginas));
        String termino = normalizar(consulta);
        String[] palabras = palabras(termino);
        // Los precios guardados se piden a la base de datos mientras se espera a las tiendas.
        CompletableFuture<List<ObservacionPrecio>> guardadas = guardadasEnParalelo(palabras);
        List<Captura> capturas = capturarCatalogos(List.of(termino), comercios, hasta);
        capturas = conRespaldo(capturas, guardadas.join());
        Consulta resultado = agrupar(capturas, coincide(palabras));
        boolean paginado = comercios.stream().anyMatch(c -> c.urlBusquedaPagina("https://x/search?q=", 2) != null);
        return new Consulta(conPreciosDeAyer(resultado.productos()), resultado.fuentes(), hasta,
                paginado && hasta < MAX_PAGINAS);
    }

    /** Palabras de la búsqueda reducidas a su raíz ("tomates" → "tomat"). */
    private static String[] palabras(String termino) {
        String[] palabras = variantes(termino)
                .replaceAll("\\b(aceite|conserva|detergente|fiambre|fideo|helado|huevo|jugo|mantequilla|"
                        + "mermelada|pasta|queso)s\\b", "$1")
                .split(" ");
        for (int i = 0; i < palabras.length; i++) {
            palabras[i] = EquivalenciaProductos.raiz(palabras[i]);
        }
        return palabras;
    }

    // Filtro: el producto debe contener todas las palabras buscadas.
    private static Predicate<ObservacionPrecio> coincide(String[] palabras) {
        return precio -> {
            String nombre = variantes(normalizar(precio.nombre()));
            return Arrays.stream(palabras).allMatch(nombre::contains);
        };
    }

    /**
     * Respuesta rápida: solo los precios guardados en la base de datos (de la tarea diaria y de
     * búsquedas anteriores) de los últimos 7 días, sin consultar las tiendas. La app la muestra
     * mientras espera la búsqueda en vivo.
     */
    Consulta consultarGuardado(String consulta, List<Comercio> comercios) {
        String[] palabras = palabras(normalizar(consulta));
        if (palabras.length == 0 || palabras[0].isBlank()) {
            return new Consulta(List.of(), List.of());
        }
        List<ObservacionPrecio> guardadas;
        try {
            guardadas = servicio.buscarCatalogo(palabras[0]);
        } catch (RuntimeException error) {
            LOG.warn("No se pudieron leer los precios guardados ({})", error.getClass().getSimpleName());
            return new Consulta(List.of(), List.of());
        }
        OffsetDateTime desde = OffsetDateTime.now().minusDays(7);
        Map<Comercio, List<ObservacionPrecio>> porComercio = new java.util.EnumMap<>(Comercio.class);
        for (ObservacionPrecio precio : guardadas) {
            if (comercios.contains(precio.comercio()) && precio.observadoEn().isAfter(desde)) {
                porComercio.computeIfAbsent(precio.comercio(), c -> new java.util.ArrayList<>()).add(precio);
            }
        }
        List<Captura> capturas = new java.util.ArrayList<>();
        porComercio.forEach((comercio, precios) -> capturas.add(new Captura(
                new Fuente(comercio, "ULTIMA_CAPTURA", precios.stream().map(ObservacionPrecio::observadoEn)
                        .max(Comparator.naturalOrder()).orElse(null)),
                precios, Instant.now())));
        Consulta resultado = agrupar(capturas, coincide(palabras));
        return new Consulta(conPreciosDeAyer(resultado.productos()), resultado.fuentes(), 1, true);
    }

    /**
     * Si una cadena no responde (por ejemplo, porque bloquea servidores fuera de Chile), usa los
     * últimos precios guardados en la base de datos para esa cadena. Se marcan como ULTIMA_CAPTURA.
     */
    private List<Captura> conRespaldo(List<Captura> capturas, List<ObservacionPrecio> guardadas) {
        if (guardadas.isEmpty()) {
            return capturas;
        }
        return conGuardadosRecientes(conRespaldoSinRespuesta(capturas, guardadas), guardadas);
    }

    /** Una sola consulta a la base de datos por búsqueda, hecha en paralelo con la descarga de las tiendas. */
    private CompletableFuture<List<ObservacionPrecio>> guardadasEnParalelo(String[] palabras) {
        if (palabras.length == 0 || palabras[0].isBlank()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return servicio.buscarCatalogo(palabras[0]);
            } catch (RuntimeException error) {
                LOG.warn("No se pudieron leer los precios guardados ({})", error.getClass().getSimpleName());
                return List.<ObservacionPrecio>of();
            }
        }, consultasBase);
    }

    /**
     * Completa la búsqueda en vivo con productos que coinciden y que la tarea diaria leyó en las
     * últimas 36 horas, aunque el buscador de la tienda no los muestre en sus primeras páginas.
     */
    private List<Captura> conGuardadosRecientes(List<Captura> capturas, List<ObservacionPrecio> guardadas) {
        var vistos = new java.util.HashSet<String>();
        var comercios = java.util.EnumSet.noneOf(Comercio.class);
        for (Captura captura : capturas) {
            comercios.add(captura.fuente.comercio);
            for (ObservacionPrecio precio : captura.precios) {
                vistos.add(precio.comercio() + ":" + precio.productoId());
            }
        }
        OffsetDateTime desde = OffsetDateTime.now().minusHours(36);
        var resultado = new java.util.ArrayList<>(capturas);
        for (Comercio comercio : comercios) {
            List<ObservacionPrecio> extra = guardadas.stream()
                    .filter(precio -> precio.comercio() == comercio && precio.observadoEn().isAfter(desde)
                            && !vistos.contains(precio.comercio() + ":" + precio.productoId()))
                    .toList();
            if (!extra.isEmpty()) {
                OffsetDateTime ultima = extra.stream().map(ObservacionPrecio::observadoEn)
                        .max(Comparator.naturalOrder()).orElse(null);
                resultado.add(new Captura(new Fuente(comercio, "ULTIMA_CAPTURA", ultima), extra, Instant.now()));
            }
        }
        return resultado;
    }

    // Para las tiendas que no respondieron, usa los precios guardados en la base.
    private List<Captura> conRespaldoSinRespuesta(List<Captura> capturas, List<ObservacionPrecio> guardadas) {
        var sinRespuesta = new java.util.LinkedHashSet<Comercio>();
        var conRespuesta = java.util.EnumSet.noneOf(Comercio.class);
        for (Captura captura : capturas) {
            if ("NO_DISPONIBLE".equals(captura.fuente.estado)) {
                sinRespuesta.add(captura.fuente.comercio);
            } else {
                conRespuesta.add(captura.fuente.comercio);
            }
        }
        sinRespuesta.removeAll(conRespuesta);
        if (sinRespuesta.isEmpty()) {
            return capturas;
        }
        var resultado = new java.util.ArrayList<>(capturas);
        for (Comercio comercio : sinRespuesta) {
            List<ObservacionPrecio> deLaCadena = guardadas.stream()
                    .filter(precio -> precio.comercio() == comercio).toList();
            if (!deLaCadena.isEmpty()) {
                OffsetDateTime ultima = deLaCadena.stream().map(ObservacionPrecio::observadoEn)
                        .max(Comparator.naturalOrder()).orElse(null);
                resultado.add(new Captura(new Fuente(comercio, "ULTIMA_CAPTURA", ultima), deLaCadena, Instant.now()));
            }
        }
        return resultado;
    }

    // Productos de una categoría (yogures, quesos…) en todas las tiendas.
    Consulta consultarRubro(Comercio.Rubro rubro, Comercio filtro) {
        List<Comercio> comercios = filtro == null ? COMERCIOS : List.of(filtro);
        var pendientes = new java.util.ArrayList<CompletableFuture<Captura>>();
        for (Comercio comercio : comercios) {
            String ruta = comercio.rutaRubro(rubro);
            if (ruta != null) {
                pendientes.add(buscar(comercio, comercio.urlPagina(ruta, 1)));
            }
        }
        Consulta resultado = agrupar(pendientes.stream().map(CompletableFuture::join).toList(), precio -> true);
        return new Consulta(conPreciosDeAyer(resultado.productos()), resultado.fuentes());
    }

    /**
     * Agrega a cada oferta el último precio leído antes de hoy (hora de Santiago). Si la base de datos
     * no responde, la búsqueda sigue sin esa comparación.
     */
    private List<Producto> conPreciosDeAyer(List<Producto> productos) {
        var buscados = new java.util.HashSet<RepositorioPrecios.ProductoTienda>();
        for (Producto producto : productos) {
            for (Oferta oferta : producto.ofertas()) {
                buscados.add(new RepositorioPrecios.ProductoTienda(oferta.comercio(), oferta.productoId()));
            }
        }
        Map<RepositorioPrecios.ProductoTienda, Integer> ayer;
        try {
            java.time.ZoneId santiago = java.time.ZoneId.of("America/Santiago");
            OffsetDateTime hoy = java.time.LocalDate.now(santiago).atStartOfDay(santiago).toOffsetDateTime();
            ayer = servicio.preciosAnteriores(buscados, hoy);
        } catch (RuntimeException error) {
            LOG.warn("No se pudieron leer los precios del día anterior ({})", error.getClass().getSimpleName());
            return productos;
        }
        if (ayer.isEmpty()) {
            return productos;
        }
        return productos.stream().map(producto -> new Producto(producto.nombre(),
                producto.ofertas().stream().map(oferta -> oferta.conPrecioAyer(
                        ayer.get(new RepositorioPrecios.ProductoTienda(oferta.comercio(), oferta.productoId()))))
                        .toList(),
                producto.claveCarrito(), producto.urlImagen())).toList();
    }

    // Agrupa los precios por producto, junta los equivalentes y ordena por cantidad de tiendas.
    private Consulta agrupar(List<Captura> capturas, Predicate<ObservacionPrecio> coincide) {
        Map<String, List<Coincidencia>> grupos = new LinkedHashMap<>();
        for (Captura captura : capturas) {
            boolean vigente = "RECIENTE".equals(captura.fuente.estado);
            for (ObservacionPrecio precio : captura.precios) {
                if (coincide.test(precio)) {
                    grupos.computeIfAbsent(IdentidadProducto.clave(precio), ignorado -> new java.util.ArrayList<>())
                            .add(new Coincidencia(precio, vigente));
                }
            }
        }
        List<Producto> productos = fusionarEquivalentes(grupos.values().stream().map(this::producto).toList())
                .stream()
                .sorted(Comparator.comparingLong(this::tiendasRecientes).reversed()
                        .thenComparing(producto -> normalizar(producto.nombre)))
                .limit(400).toList();
        return new Consulta(productos, fuentes(capturas));
    }

    // Relee las tiendas para las búsquedas dadas y espera a que todo quede guardado.
    List<Fuente> actualizarFuentes(List<String> consultas) {
        List<Fuente> fuentes = fuentes(capturarCatalogos(
                consultas.stream().map(CatalogoEnVivo::normalizar).distinct().toList(), COMERCIOS));
        // Quien llama (comparar carrito) lee enseguida la base de datos: espera a que se guarde todo.
        esperarGuardados();
        return fuentes;
    }

    /**
     * Guarda los precios leídos sin hacer esperar la respuesta de la búsqueda. Se guardan en orden,
     * uno tras otro, en una hebra aparte.
     */
    private void guardarEnSegundoPlano(List<ObservacionPrecio> precios) {
        synchronized (this) {
            guardando = guardando.thenRunAsync(() -> {
                try {
                    servicio.importar(precios);
                } catch (RuntimeException error) {
                    LOG.warn("{}: no se pudieron guardar {} precios ({})", precios.get(0).comercio(),
                            precios.size(), error.getClass().getSimpleName());
                }
            }, guardado);
        }
    }

    /** Espera a que terminen los guardados pendientes. */
    void esperarGuardados() {
        CompletableFuture<Void> pendiente;
        synchronized (this) {
            pendiente = guardando;
        }
        try {
            pendiente.join();
        } catch (RuntimeException error) {
            // Cada guardado ya registra su propio error.
        }
    }

    // Igual que la anterior, pero también relee páginas de pasillos específicos.
    List<Fuente> actualizarFuentes(List<String> consultas, List<ExploradorCatalogos.Ruta> rutas) {
        var actualizaciones = rutas.stream().map(ruta -> CompletableFuture.runAsync(() -> {
            try {
                explorador.cargar(ruta);
            } catch (IOException | RuntimeException error) {
                LOG.warn("{}: no se pudo actualizar la página elegida ({})",
                        ruta.comercio(), error.getClass().getSimpleName());
            }
        }, descargas)).toArray(CompletableFuture<?>[]::new);
        var fuentes = actualizarFuentes(consultas);
        CompletableFuture.allOf(actualizaciones).join();
        return fuentes;
    }

    // Recolección programada de una tienda: búsquedas frecuentes y rubros.
    long actualizarProgramado(Comercio comercio) {
        var urls = new java.util.LinkedHashSet<String>();
        for (String consulta : BUSQUEDAS_PROGRAMADAS) {
            String url = comercio.urlBusqueda(consulta);
            if (url != null && !url.equals(comercio.catalogo())) {
                urls.add(url);
            }
        }
        for (Comercio.Rubro rubro : Comercio.Rubro.values()) {
            String ruta = comercio.rutaRubro(rubro);
            if (ruta != null) {
                urls.add(comercio.urlPagina(ruta, 1));
            }
        }
        long paginas = reciente(capturarBase(comercio).join()) ? 1 : 0;
        for (String url : urls) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            if (reciente(buscar(comercio, url).join())) {
                paginas++;
            }
        }
        return paginas;
    }

    /**
     * Actualiza todas las tiendas a la vez (una hebra por tienda) y devuelve cuántas páginas con
     * precios recientes se leyeron en cada una. La usa la tarea diaria de Cloud Scheduler.
     */
    /** Resumen de la tarea diaria en una tienda. */
    record Avance(int paginas, int productos, int nuevos) {}

    /** Páginas como máximo por tienda y por pasillo en la tarea diaria, para no abusar de los sitios. */
    private static final int MAX_PAGINAS_TIENDA = entero("ABARRATO_TAREA_PAGINAS", 400, 10, 3000);
    private static final int MAX_PAGINAS_PASILLO = 60;
    /** Pausa entre páginas de una misma tienda. */
    private static final long PAUSA_MS = 400;

    /**
     * Tarea diaria: en cada tienda, en paralelo, recorre todos sus pasillos página por página y luego
     * las búsquedas programadas. Se detiene al llegar a {@code minutos} para caber en el plazo de
     * Cloud Scheduler. Solo guarda filas nuevas cuando un precio cambió.
     */
    Map<Comercio, Avance> actualizarTodo(int minutos) {
        Instant limite = Instant.now().plus(Duration.ofMinutes(minutos));
        var tiendas = Executors.newFixedThreadPool(COMERCIOS.size());
        try {
            Map<Comercio, CompletableFuture<Avance>> pendientes = new java.util.EnumMap<>(Comercio.class);
            for (Comercio comercio : COMERCIOS) {
                pendientes.put(comercio, CompletableFuture.supplyAsync(() -> recorrerTienda(comercio, limite), tiendas));
            }
            Map<Comercio, Avance> resumen = new java.util.EnumMap<>(Comercio.class);
            pendientes.forEach((comercio, tarea) -> resumen.put(comercio, tarea.join()));
            return resumen;
        } finally {
            tiendas.shutdown();
        }
    }

    // Tarea diaria: recorre los pasillos y búsquedas de una tienda hasta el tiempo límite.
    private Avance recorrerTienda(Comercio comercio, Instant limite) {
        int paginas = 0;
        int productos = 0;
        int nuevos = 0;
        // 1. Pasillos completos.
        List<ExploradorCatalogos.Pasillo> pasillos;
        try {
            pasillos = explorador.menu(comercio).pasillos();
        } catch (RuntimeException error) {
            LOG.warn("{}: no se pudieron leer los pasillos ({})", comercio, error.getClass().getSimpleName());
            pasillos = List.of();
        }
        for (ExploradorCatalogos.Pasillo pasillo : pasillos) {
            for (int numero = 1; numero <= MAX_PAGINAS_PASILLO; numero++) {
                if (paginas >= MAX_PAGINAS_TIENDA || Instant.now().isAfter(limite)
                        || Thread.currentThread().isInterrupted()) {
                    return new Avance(paginas, productos, nuevos);
                }
                ExploradorCatalogos.Recorrido recorrido;
                try {
                    recorrido = explorador.recorrer(new ExploradorCatalogos.Ruta(comercio, pasillo.id(), numero));
                } catch (IOException | RuntimeException error) {
                    break;
                }
                paginas++;
                productos += recorrido.productos();
                nuevos += recorrido.nuevos();
                pausa();
                if (!recorrido.siguiente()) {
                    break;
                }
            }
        }
        // 2. Búsquedas programadas (sirven en tiendas con pocos pasillos conocidos).
        for (String consulta : BUSQUEDAS_PROGRAMADAS) {
            if (paginas >= MAX_PAGINAS_TIENDA || Instant.now().isAfter(limite)) {
                break;
            }
            String url = comercio.urlBusqueda(consulta);
            if (url == null || url.equals(comercio.catalogo())) {
                continue;
            }
            try {
                var precios = capturador.capturarPagina(comercio, url);
                paginas++;
                productos += precios.size();
                nuevos += servicio.importarSiCambio(precios);
            } catch (IOException | RuntimeException error) {
                // Búsqueda sin resultados o tienda que no respondió: se sigue con la próxima.
            }
            pausa();
        }
        return new Avance(paginas, productos, nuevos);
    }

    // Pausa corta entre páginas para no saturar a la tienda.
    private static void pausa() {
        try {
            Thread.sleep(PAUSA_MS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    // true si la captura se leyó ahora y no es un respaldo.
    private static boolean reciente(Captura captura) {
        return "RECIENTE".equals(captura.fuente.estado);
    }

    // Lee la portada de una tienda en segundo plano.
    private CompletableFuture<Captura> capturarBase(Comercio comercio) {
        return CompletableFuture.supplyAsync(() -> cache.compute(comercio,
                (clave, anterior) -> actualizar(clave, null, anterior)), descargas);
    }

    // Lee las búsquedas en las tiendas indicadas (una página por tienda).
    private List<Captura> capturarCatalogos(List<String> consultas, List<Comercio> comercios) {
        return capturarCatalogos(consultas, comercios, 1);
    }

    // Lee las búsquedas en las tiendas indicadas, hasta 'paginas' páginas por tienda, en paralelo.
    private List<Captura> capturarCatalogos(List<String> consultas, List<Comercio> comercios, int paginas) {
        var pendientes = new java.util.LinkedHashSet<CompletableFuture<Captura>>();
        for (String consulta : consultas) {
            if (!consulta.isBlank()) {
                for (Comercio comercio : comercios) {
                    String url = comercio.urlBusqueda(consulta);
                    if (url != null && !url.equals(comercio.catalogo())) {
                        pendientes.add(buscar(comercio, url));
                        // Páginas siguientes del buscador, solo cuando la app pide "ver más".
                        for (int pagina = 2; pagina <= paginas; pagina++) {
                            String siguiente = comercio.urlBusquedaPagina(url, pagina);
                            if (siguiente != null) {
                                pendientes.add(buscar(comercio, siguiente));
                            }
                        }
                    }
                }
            }
        }
        for (Comercio comercio : comercios) {
            pendientes.add(capturarBase(comercio));
        }
        return pendientes.stream().map(CompletableFuture::join).toList();
    }

    // Evita leer dos veces la misma URL al mismo tiempo: reutiliza la lectura en curso.
    private synchronized CompletableFuture<Captura> buscar(Comercio comercio, String url) {
        Busqueda clave = new Busqueda(comercio, url);
        CompletableFuture<Captura> existente = busquedas.get(clave);
        Captura anterior = existente == null ? null : existente.getNow(null);
        if (existente != null && (anterior == null || Instant.now().isBefore(anterior.renovarEn))) {
            return existente;
        }
        var nueva = CompletableFuture.supplyAsync(() -> actualizar(comercio, url, anterior), descargas);
        busquedas.put(clave, nueva);
        if (busquedas.size() > MAX_BUSQUEDAS_GUARDADAS) {
            busquedas.remove(busquedas.keySet().iterator().next());
        }
        return nueva;
    }

    // Resume el estado de cada tienda para la respuesta.
    private List<Fuente> fuentes(List<Captura> capturas) {
        Map<Comercio, Fuente> fuentes = new java.util.EnumMap<>(Comercio.class);
        for (Captura captura : capturas) {
            Fuente fuente = captura.fuente;
            fuentes.merge(fuente.comercio, fuente, (primera, otra) ->
                    "RECIENTE".equals(primera.estado) ? primera : otra);
        }
        return List.copyOf(fuentes.values());
    }

    // Convierte un precio guardado en un producto de una sola tienda.
    static Producto productoIndividual(ObservacionPrecio precio, boolean vigente) {
        return new Producto(precio.nombre(), List.of(oferta(precio, vigente)),
                IdentidadProducto.clave(precio), precio.urlImagen());
    }

    // Convierte un precio guardado en la oferta que ve la app (con promoción si la hay).
    private static Oferta oferta(ObservacionPrecio precio, boolean vigente) {
        return new Oferta(precio.comercio(), precio.productoId(), precio.precioClp(), precio.urlFuente(),
                precio.observadoEn(), vigente, precio.precioAntesClp(), precio.precioOfertaClp(),
                precio.condicionOferta(), null);
    }

    // Arma un producto a partir de los precios de varias tiendas que comparten clave.
    private Producto producto(List<Coincidencia> coincidencias) {
        Map<Comercio, Oferta> ofertas = new LinkedHashMap<>();
        for (Coincidencia coincidencia : coincidencias) {
            ObservacionPrecio precio = coincidencia.precio;
            Oferta oferta = oferta(precio, coincidencia.vigente);
            ofertas.merge(precio.comercio(), oferta,
                    (primera, otra) -> primera.vigente == otra.vigente
                            ? (primera.observadoEn.isBefore(otra.observadoEn) ? otra : primera)
                            : (primera.vigente ? primera : otra));
        }
        List<Oferta> ordenadas = ofertas.values().stream()
                .sorted(Comparator.comparing(Oferta::vigente).reversed()
                        .thenComparingInt(Oferta::precioClp))
                .toList();
        ObservacionPrecio primero = coincidencias.get(0).precio;
        String imagen = coincidencias.stream().map(item -> item.precio.urlImagen())
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        return new Producto(primero.nombre(), ordenadas, IdentidadProducto.clave(primero), imagen);
    }

    /**
     * Junta en una sola fila el mismo producto publicado por cadenas distintas cuando sus códigos no
     * coinciden (por ejemplo Líder frente a Jumbo). Solo une grupos sin cadenas en común.
     */
    static List<Producto> fusionarEquivalentes(List<Producto> productos) {
        /*
         * Solo pueden ser el mismo producto los que tienen el mismo tamaño ("1 L", "500 g"), así que se
         * agrupan primero por tamaño en un HashMap y se compara únicamente dentro de cada grupo. Pasa de
         * comparar todos contra todos (n²) a grupos pequeños; los productos sin tamaño no se fusionan.
         */
        Producto[] vivos = productos.toArray(new Producto[0]);
        EquivalenciaProductos.Firma[] firmas = new EquivalenciaProductos.Firma[vivos.length];
        java.util.Map<String, List<Integer>> porMedida = new java.util.HashMap<>();
        for (int i = 0; i < vivos.length; i++) {
            firmas[i] = EquivalenciaProductos.firma(vivos[i].nombre);
            if (firmas[i].medida() != null) {
                porMedida.computeIfAbsent(firmas[i].medida(), m -> new java.util.ArrayList<>()).add(i);
            }
        }
        for (List<Integer> grupo : porMedida.values()) {
            for (int x = 0; x < grupo.size(); x++) {
                int i = grupo.get(x);
                if (vivos[i] == null) {
                    continue;
                }
                for (int y = x + 1; y < grupo.size(); y++) {
                    int j = grupo.get(y);
                    Producto a = vivos[i];
                    Producto b = vivos[j];
                    if (b == null || !sinCadenasEnComun(a, b)
                            || !EquivalenciaProductos.equivalentes(firmas[i], firmas[j])) {
                        continue;
                    }
                    // Se conserva el nombre y la clave del grupo con más cadenas (normalmente con la marca).
                    Producto base = b.ofertas.size() > a.ofertas.size() ? b : a;
                    Producto otro = base == a ? b : a;
                    List<Oferta> ofertas = new java.util.ArrayList<>(base.ofertas);
                    ofertas.addAll(otro.ofertas);
                    ofertas.sort(Comparator.comparing(Oferta::vigente).reversed().thenComparingInt(Oferta::precioClp));
                    String imagen = base.urlImagen != null ? base.urlImagen : otro.urlImagen;
                    vivos[i] = new Producto(base.nombre, List.copyOf(ofertas), base.claveCarrito, imagen);
                    firmas[i] = EquivalenciaProductos.firma(base.nombre);
                    vivos[j] = null;
                }
            }
        }
        List<Producto> resultado = new java.util.ArrayList<>(vivos.length);
        for (Producto producto : vivos) {
            if (producto != null) {
                resultado.add(producto);
            }
        }
        return resultado;
    }

    // true si dos productos no comparten tienda (requisito para fusionarlos).
    private static boolean sinCadenasEnComun(Producto a, Producto b) {
        for (Oferta oferta : a.ofertas) {
            for (Oferta otra : b.ofertas) {
                if (oferta.comercio == otra.comercio) {
                    return false;
                }
            }
        }
        return true;
    }

    // Cuántas tiendas tienen precio vigente para el producto (para ordenar).
    private long tiendasRecientes(Producto producto) {
        return producto.ofertas.stream().filter(Oferta::vigente).count();
    }

    // Lee una página si la anterior venció; si falla, conserva la última captura.
    private Captura actualizar(Comercio comercio, String url, Captura anterior) {
        if (anterior != null && Instant.now().isBefore(anterior.renovarEn)) {
            return anterior;
        }
        try {
            List<ObservacionPrecio> precios = url == null
                    ? capturador.capturar(comercio) : capturador.capturarPagina(comercio, url);
            guardarEnSegundoPlano(precios);
            Fuente fuente = new Fuente(comercio, "RECIENTE", precios.get(0).observadoEn());
            return new Captura(fuente, precios, Instant.now().plus(VIGENCIA));
        } catch (IOException | RuntimeException error) {
            LOG.warn("{}: captura a demanda falló ({}: {})", comercio, error.getClass().getSimpleName(),
                    error.getMessage());
            if (anterior == null) {
                return new Captura(new Fuente(comercio, "NO_DISPONIBLE", null),
                        List.of(), Instant.now().plus(REINTENTO));
            }
            String estado = anterior.precios.isEmpty() ? "NO_DISPONIBLE" : "ULTIMA_CAPTURA";
            return new Captura(new Fuente(comercio, estado, anterior.fuente.observadoEn),
                    anterior.precios, Instant.now().plus(REINTENTO));
        }
    }

    // Texto sin tildes, en minúsculas y sin símbolos, para comparar nombres.
    private static String normalizar(String valor) {
        return Normalizer.normalize(valor, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    // Unifica variantes de escritura: yoghurt → yogur, cereales → cereal.
    private static String variantes(String termino) {
        return termino.replaceAll("\\byog(?:h?urts?|ur(?:es)?)\\b", "yogur")
                .replaceAll("\\bcereales\\b", "cereal");
    }

    // Lee un número de una variable de entorno, con valor por defecto y límites.
    static int entero(String variable, int predeterminado, int minimo, int maximo) {
        try {
            String valor = System.getenv(variable);
            return valor == null || valor.isBlank() ? predeterminado
                    : Math.max(minimo, Math.min(maximo, Integer.parseInt(valor.strip())));
        } catch (NumberFormatException error) {
            return predeterminado;
        }
    }

    // Al apagar el servidor, detiene las hebras de descarga y guardado.
    @PreDestroy
    void cerrar() {
        descargas.shutdownNow();
        consultasBase.shutdownNow();
        // Se deja terminar lo que estaba por guardarse.
        guardado.shutdown();
        try {
            guardado.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}
