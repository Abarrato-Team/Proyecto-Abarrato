package cl.abarrato.precios;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

@Service
final class ExploradorCatalogos {
    record Pasillo(String id, String nombre) {}
    record Menu(String urlOficial, List<Pasillo> pasillos) {}
    record Pagina(List<CatalogoEnVivo.Producto> productos, boolean siguiente, boolean reciente) {}
    record Ruta(Comercio comercio, String pasillo, int pagina) {
        Ruta {
            if (comercio == null || pasillo == null || pasillo.length() > 160 || pagina < 1 || pagina > 2000
                    || (comercio == Comercio.DEL_PEDREGAL && pagina != 1)
                    || !pasillo.matches(sintaxis(comercio))) {
                throw new IllegalArgumentException("Pasillo o página inválidos");
            }
        }
    }

    private record Guardado<T>(T contenido, Instant vence) {
        boolean vigente() {
            return Instant.now().isBefore(vence);
        }
    }

    private record CapturaPagina(List<ObservacionPrecio> precios, boolean siguiente, boolean reciente) {
        Pagina respuesta() {
            return new Pagina(precios.stream().map(precio -> CatalogoEnVivo.productoIndividual(precio, reciente))
                    .toList(), siguiente, reciente);
        }
    }

    private final CapturadorPrecios capturador;
    private final ExtractorPrecios extractor;
    private final ServicioPrecios servicio;
    private final ObjectMapper json;
    private final Map<Ruta, Guardado<CapturaPagina>> paginas = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<Comercio, Guardado<Menu>> menus = new EnumMap<>(Comercio.class);

    ExploradorCatalogos(CapturadorPrecios capturador, ExtractorPrecios extractor,
            ServicioPrecios servicio, ObjectMapper json) {
        this.capturador = capturador;
        this.extractor = extractor;
        this.servicio = servicio;
        this.json = json;
    }

    Menu menu(Comercio comercio) {
        synchronized (menus) {
            var guardado = menus.get(comercio);
            if (guardado != null && guardado.vigente()) {
                return guardado.contenido;
            }
        }
        List<Pasillo> pasillos = switch (comercio) {
            case JUMBO, SANTA_ISABEL, CUGAT -> descubrirPasillos(comercio);
            case LIDER -> List.of(
                    new Pasillo("supermercado", "Vitrina Supermercado"),
                    new Pasillo("productos-de-despensa-y-abarrotes", "Despensa y abarrotes"),
                    new Pasillo("lacteos", "Lácteos"), new Pasillo("productos-de-limpieza", "Limpieza"),
                    new Pasillo("arroz", "Arroz"), new Pasillo("pastas", "Pastas"),
                    new Pasillo("aceite", "Aceites"), new Pasillo("huevos", "Huevos"),
                    new Pasillo("pollo", "Pollo"), new Pasillo("detergente", "Detergentes"));
            case TOTTUS -> List.of(
                    new Pasillo("CATG27054/Tottus", "Catálogo Tottus"),
                    new Pasillo("CATG27055/Despensa", "Despensa"), new Pasillo("CATG27069/Carnes", "Carnes"),
                    new Pasillo("CATG27070/Frutas-y-Verduras", "Frutas y verduras"),
                    new Pasillo("CATG27139/Lacteos-y-Quesos", "Lácteos y quesos"),
                    new Pasillo("CATG27073/Congelados", "Congelados"),
                    new Pasillo("CATG29182/Bebestibles", "Bebestibles"),
                    new Pasillo("CATG27074/Aseo-y-Limpieza", "Aseo y limpieza"),
                    new Pasillo("CATG27078/Mascotas", "Mascotas"));
            case UNIMARC -> pasillos(
                    "despensa", "Despensa", "carnes", "Carnes", "frutas-y-verduras", "Frutas y verduras",
                    "lacteos-huevos-y-refrigerados", "Lácteos, huevos y refrigerados",
                    "quesos-y-fiambres", "Quesos y fiambres", "panaderia-y-pasteleria", "Panadería y pastelería",
                    "congelados", "Congelados", "desayuno-y-dulces", "Desayuno y dulces",
                    "bebidas-y-licores", "Bebidas y licores", "limpieza", "Limpieza",
                    "perfumeria", "Perfumería", "bebes-y-ninos", "Bebés y niños", "mascotas-y-hogar", "Mascotas y hogar");
            case ALVI -> pasillosAlvi();
            case DEL_PEDREGAL -> List.of(new Pasillo("vitrina", "Vitrina disponible"));
        };
        Map<String, Pasillo> conRubros = new LinkedHashMap<>();
        pasillos.forEach(pasillo -> conRubros.put(pasillo.id, pasillo));
        for (Comercio.Rubro rubro : Comercio.Rubro.values()) {
            String ruta = comercio.rutaRubro(rubro);
            if (ruta != null) {
                conRubros.putIfAbsent(ruta, new Pasillo(ruta, rubro.nombre));
            }
        }
        var menu = new Menu(urlOficial(comercio), List.copyOf(conRubros.values()));
        synchronized (menus) {
            menus.put(comercio, new Guardado<>(menu, Instant.now().plus(Duration.ofMinutes(10))));
        }
        return menu;
    }

    Pagina cargar(Ruta ruta) throws IOException {
        synchronized (paginas) {
            var guardada = paginas.get(ruta);
            if (guardada != null && guardada.vigente()) {
                return guardada.contenido.respuesta();
            }
        }
        CapturaPagina resultado;
        try {
            Document documento = capturador.descargar(ruta.comercio.urlPagina(ruta.pasillo, ruta.pagina));
            var precios = extractor.extraerPagina(ruta.comercio, documento, OffsetDateTime.now(ZoneOffset.UTC));
            resultado = new CapturaPagina(precios, siguiente(ruta, documento), true);
            if (!precios.isEmpty()) {
                servicio.importar(precios);
            }
        } catch (IOException error) {
            synchronized (paginas) {
                var ultima = paginas.get(ruta);
                if (ultima == null) {
                    throw error;
                }
                if (ultima.vigente()) {
                    return ultima.contenido.respuesta();
                }
                var anterior = new CapturaPagina(ultima.contenido.precios, ultima.contenido.siguiente, false);
                paginas.put(ruta, new Guardado<>(anterior, Instant.now().plusSeconds(45)));
                return anterior.respuesta();
            }
        }
        synchronized (paginas) {
            paginas.put(ruta, new Guardado<>(resultado, Instant.now().plus(Duration.ofMinutes(2))));
            if (paginas.size() > 256) {
                paginas.remove(paginas.keySet().iterator().next());
            }
        }
        return resultado.respuesta();
    }

    private List<Pasillo> descubrirPasillos(Comercio comercio) {
        Map<String, Pasillo> encontrados = new LinkedHashMap<>();
        List<Pasillo> baseMenu = comercio == Comercio.CUGAT
                ? pasillos("despensa/arroz-legumbres-y-semillas", "Arroz, legumbres y semillas",
                        "despensa", "Despensa", "carniceria", "Carnicería", "lacteos", "Lácteos",
                        "fiambreria-embutidos-y-quesos", "Fiambrería, embutidos y quesos",
                        "la-gran-feria-cugat", "Frutas y verduras", "mundo-congelados", "Congelados",
                        "limpieza-y-aseo", "Limpieza y aseo", "cuidado-personal", "Cuidado personal")
                : pasillos("despensa", "Despensa", "lacteos-huevos-y-congelados/leches", "Leches");
        baseMenu.forEach(pasillo -> encontrados.put(pasillo.id, pasillo));
        Document documento;
        try {
            documento = capturador.descargar(comercio.catalogo());
        } catch (IOException error) {
            return baseMenu;
        }
        String selector = comercio == Comercio.CUGAT ? "a[href*=/categoria-producto/]"
                : ".categories-menu-dropdown .categories-list > .category-item-wrapper > a[href]";
        URI base = URI.create(comercio.catalogo());
        for (Element enlace : documento.select(selector)) {
            URI destino;
            try {
                destino = base.resolve(enlace.attr("href"));
            } catch (IllegalArgumentException error) {
                continue;
            }
            String ruta = destino.getPath();
            if (!base.getHost().equals(destino.getHost()) || ruta == null || enlace.text().isBlank()) {
                continue;
            }
            String id = comercio == Comercio.CUGAT
                    ? ruta.replaceFirst("^/categoria-producto/", "").replaceFirst("/$", "")
                    : ruta.replaceFirst("^/", "");
            if (id.matches("[a-z0-9-]+")) {
                encontrados.putIfAbsent(id, new Pasillo(id, enlace.text()));
            }
        }
        return List.copyOf(encontrados.values());
    }

    private List<Pasillo> pasillosAlvi() {
        Map<String, Pasillo> encontrados = new LinkedHashMap<>();
        pasillos("abarrotes", "Abarrotes", "abarrotes/arroz", "Arroz",
                "abarrotes/aceites", "Aceites", "abarrotes/pastas", "Pastas",
                "especial-almacenero", "Especial almacenero")
                .forEach(pasillo -> encontrados.put(pasillo.id, pasillo));
        try {
            String base = "https://www.alvi.cl/category/";
            for (Element enlace : capturador.descargar("https://www.alvi.cl/sitemap/category-1.xml").select("loc")) {
                String url = enlace.text();
                if (url.startsWith(base)) {
                    String id = url.substring(base.length());
                    if (id.matches("[a-z0-9-]{1,100}")) {
                        String nombre = id.replace('-', ' ');
                        encontrados.putIfAbsent(id, new Pasillo(id,
                                Character.toUpperCase(nombre.charAt(0)) + nombre.substring(1)));
                    }
                }
            }
        } catch (IOException error) {
            // Los pasillos principales verificados siguen disponibles si falla el índice.
        }
        return List.copyOf(encontrados.values());
    }

    private static List<Pasillo> pasillos(String... pares) {
        var resultado = new java.util.ArrayList<Pasillo>();
        for (int i = 0; i < pares.length; i += 2) {
            resultado.add(new Pasillo(pares[i], pares[i + 1]));
        }
        return List.copyOf(resultado);
    }

    private static String sintaxis(Comercio comercio) {
        return switch (comercio) {
            case TOTTUS -> "CATG[0-9]{1,10}/[A-Za-z0-9-]+";
            case LIDER -> "supermercado|productos-de-despensa-y-abarrotes|lacteos|productos-de-limpieza"
                    + "|arroz|pastas|aceite|huevos|pollo|detergente|yogurt|mantequilla|cereales|avena|cafe"
                    + "|panaderia|helados|jugos|alimentos-para-perros|queso|jamon|atun|mermelada";
            case DEL_PEDREGAL -> "vitrina";
            default -> "[a-z0-9-]+(?:/[a-z0-9-]+){0,3}";
        };
    }

    private static String urlOficial(Comercio comercio) {
        return switch (comercio) {
            case TOTTUS -> "https://www.tottus.cl/tottus-cl";
            case DEL_PEDREGAL -> "https://alimentosdelpedregal.com/collections/all";
            case LIDER -> "https://super.lider.cl/";
            case CUGAT -> "https://cugat.cl/";
            default -> "https://" + URI.create(comercio.catalogo()).getHost() + "/";
        };
    }

    private boolean siguiente(Ruta ruta, Document documento) throws IOException {
        if (ruta.pagina == 2000) {
            return false;
        }
        return switch (ruta.comercio) {
            case JUMBO, SANTA_ISABEL -> {
                String href = "/" + ruta.pasillo + "?page=" + (ruta.pagina + 1);
                yield documento.select("a[href]").stream().anyMatch(e -> href.equals(e.attr("href")));
            }
            case CUGAT -> documento.selectFirst(".woocommerce-pagination a.next") != null;
            case LIDER -> documento.select(".pagination-list .pagination__link").eachText().stream()
                    .filter(numero -> numero.matches("[0-9]{1,5}"))
                    .anyMatch(numero -> Integer.parseInt(numero) > ruta.pagina);
            case TOTTUS -> {
                JsonNode datos = propiedades(documento).path("pagination");
                int porPagina = datos.path("perPage").asInt();
                yield porPagina > 0 && datos.path("currentPage").asInt() == ruta.pagina
                        && (long) ruta.pagina * porPagina < datos.path("count").asInt();
            }
            case UNIMARC, ALVI -> {
                JsonNode props = propiedades(documento);
                int total = 0;
                for (JsonNode consulta : props.at("/dehydratedState/queries")) {
                    JsonNode datos = consulta.at("/state/data");
                    total = Math.max(total, Math.max(datos.path("resource").asInt(), datos.path("resources").asInt()));
                }
                yield props.path("page").asInt() == ruta.pagina && (long) ruta.pagina * 50 < total;
            }
            case DEL_PEDREGAL -> false;
        };
    }

    private JsonNode propiedades(Document documento) throws IOException {
        Element datos = documento.selectFirst("script#__NEXT_DATA__");
        return datos == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance()
                : json.readTree(datos.data()).at("/props/pageProps");
    }
}
