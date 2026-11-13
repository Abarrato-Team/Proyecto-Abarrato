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

@Service
final class CatalogoEnVivo {
    private static final Logger LOG = LoggerFactory.getLogger(CatalogoEnVivo.class);
    private static final Duration VIGENCIA = Duration.ofMinutes(2);
    private static final Duration REINTENTO = Duration.ofSeconds(45);
    private static final int MAX_BUSQUEDAS_GUARDADAS = 160;
    static final int MAX_PAGINAS = 4;
    private static final List<Comercio> COMERCIOS = List.of(Comercio.values());
    private static final List<String> BUSQUEDAS_PROGRAMADAS = List.of(
            "arroz", "spaghetti", "aceite", "huevos", "pollo", "detergente");

    record Oferta(Comercio comercio, String productoId, int precioClp, String urlFuente,
                  OffsetDateTime observadoEn, boolean vigente) {}

    record Producto(String nombre, List<Oferta> ofertas, String claveCarrito, String urlImagen) {}

    record Fuente(Comercio comercio, String estado, OffsetDateTime observadoEn) {}

    /** {@code paginas}: páginas del buscador incluidas; {@code hayMas}: si la app puede pedir la siguiente. */
    record Consulta(List<Producto> productos, List<Fuente> fuentes, int paginas, boolean hayMas) {
        Consulta(List<Producto> productos, List<Fuente> fuentes) {
            this(productos, fuentes, 1, false);
        }
    }

    private record Captura(Fuente fuente, List<ObservacionPrecio> precios, Instant renovarEn) {}

    private record Coincidencia(ObservacionPrecio precio, boolean vigente) {}
    private record Busqueda(Comercio comercio, String url) {}

    private final CapturadorPrecios capturador;
    private final ServicioPrecios servicio;
    private final ExploradorCatalogos explorador;
    private final Map<Comercio, Captura> cache = new ConcurrentHashMap<>();
    private final Map<Busqueda, CompletableFuture<Captura>> busquedas = new LinkedHashMap<>(16, 0.75f, true);
    // En servidores con poca CPU (como el plan gratis de Render) conviene descargar menos páginas a la vez.
    private final ExecutorService descargas = Executors.newFixedThreadPool(
            entero("ABARRATO_DESCARGAS", Comercio.values().length, 1, 16));

    CatalogoEnVivo(CapturadorPrecios capturador, ServicioPrecios servicio, ExploradorCatalogos explorador) {
        this.capturador = capturador;
        this.servicio = servicio;
        this.explorador = explorador;
    }

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
        String[] palabras = variantes(termino)
                .replaceAll("\\b(aceite|conserva|detergente|fiambre|fideo|helado|huevo|jugo|mantequilla|"
                        + "mermelada|pasta|queso)s\\b", "$1")
                .split(" ");
        for (int i = 0; i < palabras.length; i++) {
            palabras[i] = EquivalenciaProductos.raiz(palabras[i]);
        }
        List<Captura> capturas = conRespaldo(capturarCatalogos(List.of(termino), comercios, hasta), palabras);
        Consulta resultado = agrupar(capturas, precio -> {
            String nombre = variantes(normalizar(precio.nombre()));
            return Arrays.stream(palabras).allMatch(nombre::contains);
        });
        boolean paginado = comercios.stream().anyMatch(c -> c.urlBusquedaPagina("https://x/search?q=", 2) != null);
        return new Consulta(resultado.productos(), resultado.fuentes(), hasta, paginado && hasta < MAX_PAGINAS);
    }

    /**
     * Si una cadena no responde (por ejemplo, porque bloquea servidores fuera de Chile), usa los
     * últimos precios guardados en la base de datos para esa cadena. Se marcan como ULTIMA_CAPTURA.
     */
    private List<Captura> conRespaldo(List<Captura> capturas, String[] palabras) {
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
        if (sinRespuesta.isEmpty() || palabras.length == 0 || palabras[0].isBlank()) {
            return capturas;
        }
        List<ObservacionPrecio> guardadas;
        try {
            guardadas = servicio.buscarCatalogo(palabras[0]);
        } catch (RuntimeException error) {
            LOG.warn("No se pudieron leer los precios guardados ({})", error.getClass().getSimpleName());
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

    Consulta consultarRubro(Comercio.Rubro rubro, Comercio filtro) {
        List<Comercio> comercios = filtro == null ? COMERCIOS : List.of(filtro);
        var pendientes = new java.util.ArrayList<CompletableFuture<Captura>>();
        for (Comercio comercio : comercios) {
            String ruta = comercio.rutaRubro(rubro);
            if (ruta != null) {
                pendientes.add(buscar(comercio, comercio.urlPagina(ruta, 1)));
            }
        }
        return agrupar(pendientes.stream().map(CompletableFuture::join).toList(), precio -> true);
    }

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

    List<Fuente> actualizarFuentes(List<String> consultas) {
        return fuentes(capturarCatalogos(consultas.stream().map(CatalogoEnVivo::normalizar).distinct().toList(),
                COMERCIOS));
    }

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

    private static boolean reciente(Captura captura) {
        return "RECIENTE".equals(captura.fuente.estado);
    }

    private CompletableFuture<Captura> capturarBase(Comercio comercio) {
        return CompletableFuture.supplyAsync(() -> cache.compute(comercio,
                (clave, anterior) -> actualizar(clave, null, anterior)), descargas);
    }

    private List<Captura> capturarCatalogos(List<String> consultas, List<Comercio> comercios) {
        return capturarCatalogos(consultas, comercios, 1);
    }

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

    private List<Fuente> fuentes(List<Captura> capturas) {
        Map<Comercio, Fuente> fuentes = new java.util.EnumMap<>(Comercio.class);
        for (Captura captura : capturas) {
            Fuente fuente = captura.fuente;
            fuentes.merge(fuente.comercio, fuente, (primera, otra) ->
                    "RECIENTE".equals(primera.estado) ? primera : otra);
        }
        return List.copyOf(fuentes.values());
    }

    static Producto productoIndividual(ObservacionPrecio precio, boolean vigente) {
        return new Producto(precio.nombre(), List.of(oferta(precio, vigente)),
                IdentidadProducto.clave(precio), precio.urlImagen());
    }

    private static Oferta oferta(ObservacionPrecio precio, boolean vigente) {
        return new Oferta(precio.comercio(), precio.productoId(), precio.precioClp(), precio.urlFuente(),
                precio.observadoEn(), vigente);
    }

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
        List<Producto> resultado = new java.util.ArrayList<>(productos);
        List<EquivalenciaProductos.Firma> firmas = new java.util.ArrayList<>();
        for (Producto producto : resultado) {
            firmas.add(EquivalenciaProductos.firma(producto.nombre));
        }
        for (int i = 0; i < resultado.size(); i++) {
            for (int j = i + 1; j < resultado.size(); j++) {
                Producto a = resultado.get(i);
                Producto b = resultado.get(j);
                if (sinCadenasEnComun(a, b) && EquivalenciaProductos.equivalentes(firmas.get(i), firmas.get(j))) {
                    // Se conserva el nombre y la clave del grupo con más cadenas (normalmente con la marca).
                    Producto base = b.ofertas.size() > a.ofertas.size() ? b : a;
                    Producto otro = base == a ? b : a;
                    List<Oferta> ofertas = new java.util.ArrayList<>(base.ofertas);
                    ofertas.addAll(otro.ofertas);
                    ofertas.sort(Comparator.comparing(Oferta::vigente).reversed().thenComparingInt(Oferta::precioClp));
                    String imagen = base.urlImagen != null ? base.urlImagen : otro.urlImagen;
                    resultado.set(i, new Producto(base.nombre, List.copyOf(ofertas), base.claveCarrito, imagen));
                    firmas.set(i, EquivalenciaProductos.firma(base.nombre));
                    resultado.remove(j);
                    firmas.remove(j);
                    j--;
                }
            }
        }
        return resultado;
    }

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

    private long tiendasRecientes(Producto producto) {
        return producto.ofertas.stream().filter(Oferta::vigente).count();
    }

    private Captura actualizar(Comercio comercio, String url, Captura anterior) {
        if (anterior != null && Instant.now().isBefore(anterior.renovarEn)) {
            return anterior;
        }
        try {
            List<ObservacionPrecio> precios = url == null
                    ? capturador.capturar(comercio) : capturador.capturarPagina(comercio, url);
            servicio.importar(precios);
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

    private static String normalizar(String valor) {
        return Normalizer.normalize(valor, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    private static String variantes(String termino) {
        return termino.replaceAll("\\byog(?:h?urts?|ur(?:es)?)\\b", "yogur")
                .replaceAll("\\bcereales\\b", "cereal");
    }

    static int entero(String variable, int predeterminado, int minimo, int maximo) {
        try {
            String valor = System.getenv(variable);
            return valor == null || valor.isBlank() ? predeterminado
                    : Math.max(minimo, Math.min(maximo, Integer.parseInt(valor.strip())));
        } catch (NumberFormatException error) {
            return predeterminado;
        }
    }

    @PreDestroy
    void cerrar() {
        descargas.shutdownNow();
    }
}
