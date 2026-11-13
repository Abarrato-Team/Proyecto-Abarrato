package cl.abarrato.precios;

import java.net.URLEncoder;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public enum Comercio {
    JUMBO("jumbo.cl", "https://www.jumbo.cl/lacteos-huevos-y-congelados/leches"),
    ALVI("alvi.cl", "https://www.alvi.cl/ofertas/especial-almacenero"),
    SANTA_ISABEL("santaisabel.cl", "https://www.santaisabel.cl/lacteos-huevos-y-congelados/leches"),
    TOTTUS("tottus.cl", "https://www.tottus.cl/tottus-cl/lista/CATG27524/Leches-Enteras"),
    LIDER("lider.cl", "https://super.lider.cl/v/supermercado"),
    DEL_PEDREGAL("alimentosdelpedregal.com", "https://alimentosdelpedregal.com/"),
    UNIMARC("unimarc.cl", "https://www.unimarc.cl/category/lacteos-huevos-y-refrigerados/leches-y-cremas/leches-blancas"),
    CUGAT("cugat.cl", "https://cugat.cl/categoria-producto/despensa/arroz-legumbres-y-semillas/");

    private static final Map<String, String> PASILLOS = Map.ofEntries(
            Map.entry("arroz", "arroz"), Map.entry("aceite", "aceite"), Map.entry("aceites", "aceite"),
            Map.entry("pasta", "pastas"), Map.entry("pastas", "pastas"), Map.entry("fideo", "pastas"),
            Map.entry("fideos", "pastas"), Map.entry("spaghetti", "pastas"), Map.entry("tallarines", "pastas"),
            Map.entry("huevo", "huevos"), Map.entry("huevos", "huevos"),
            Map.entry("pollo", "pollo"), Map.entry("detergente", "detergente"),
            Map.entry("detergentes", "detergente"), Map.entry("leche", "leche"));

    enum Rubro {
        YOGUR("Yogures"), MANTEQUILLA("Mantequillas"), CEREAL("Cereales"),
        AVENA("Avena y granola"), CAFE("Café"),
        QUESO("Quesos"), FIAMBRE("Fiambres y embutidos"), CONSERVA("Conservas y enlatados"),
        MERMELADA("Mermeladas y dulces untables"), PAN("Panadería"),
        HELADO("Helados"), JUGO("Jugos y bebidas"), MASCOTAS("Mascotas");

        final String nombre;

        Rubro(String nombre) {
            this.nombre = nombre;
        }
    }

    private static final Map<String, Rubro> RUBROS = Map.ofEntries(
            Map.entry("yogur", Rubro.YOGUR), Map.entry("yogures", Rubro.YOGUR),
            Map.entry("yogurt", Rubro.YOGUR), Map.entry("yogurts", Rubro.YOGUR),
            Map.entry("yoghurt", Rubro.YOGUR), Map.entry("yoghurts", Rubro.YOGUR),
            Map.entry("mantequilla", Rubro.MANTEQUILLA), Map.entry("mantequillas", Rubro.MANTEQUILLA),
            Map.entry("cereal", Rubro.CEREAL), Map.entry("cereales", Rubro.CEREAL),
            Map.entry("avena", Rubro.AVENA), Map.entry("granola", Rubro.AVENA),
            Map.entry("cafe", Rubro.CAFE),
            Map.entry("queso", Rubro.QUESO), Map.entry("quesos", Rubro.QUESO),
            Map.entry("fiambre", Rubro.FIAMBRE), Map.entry("fiambres", Rubro.FIAMBRE),
            Map.entry("jamon", Rubro.FIAMBRE), Map.entry("embutidos", Rubro.FIAMBRE),
            Map.entry("conserva", Rubro.CONSERVA), Map.entry("conservas", Rubro.CONSERVA),
            Map.entry("enlatados", Rubro.CONSERVA), Map.entry("mermelada", Rubro.MERMELADA),
            Map.entry("mermeladas", Rubro.MERMELADA), Map.entry("manjar", Rubro.MERMELADA),
            Map.entry("pan", Rubro.PAN), Map.entry("panaderia", Rubro.PAN),
            Map.entry("helado", Rubro.HELADO), Map.entry("helados", Rubro.HELADO),
            Map.entry("jugo", Rubro.JUGO), Map.entry("jugos", Rubro.JUGO),
            Map.entry("mascota", Rubro.MASCOTAS), Map.entry("mascotas", Rubro.MASCOTAS),
            Map.entry("perro", Rubro.MASCOTAS), Map.entry("perros", Rubro.MASCOTAS),
            Map.entry("gato", Rubro.MASCOTAS), Map.entry("gatos", Rubro.MASCOTAS));

    private final String dominio;
    private final String catalogo;

    Comercio(String dominio, String catalogo) {
        this.dominio = dominio;
        this.catalogo = catalogo;
    }

    String catalogo() {
        return catalogo;
    }

    static final String CATALOGO_PEDREGAL = "https://alimentosdelpedregal.com/products.json?limit=250";

    String urlBusqueda(String consulta) {
        if (this == LIDER) {
            // Buscador público de Líder: los resultados vienen en el bloque __NEXT_DATA__ de la página.
            return "https://www.lider.cl/search?q=" + URLEncoder.encode(consulta, StandardCharsets.UTF_8);
        }
        if (this == DEL_PEDREGAL) {
            // Tienda Shopify: se lee el catálogo completo en JSON y se filtra por las palabras buscadas.
            return CATALOGO_PEDREGAL;
        }
        Rubro rubroExacto = RUBROS.get(consulta);
        if (rubroExacto != null) {
            String ruta = rutaRubro(rubroExacto);
            if (ruta != null) {
                return urlPagina(ruta, 1);
            }
        }
        if (this == JUMBO || this == SANTA_ISABEL) {
            String termino = URLEncoder.encode(consulta.replaceAll("\\byog(?:h?urts?|ur(?:es)?)\\b", "yoghurt"),
                    StandardCharsets.UTF_8);
            return "https://www." + dominio + "/busqueda?ft=" + termino;
        }
        for (String palabra : consulta.split(" ")) {
            Rubro rubro = RUBROS.get(palabra);
            String ruta = rubro == null ? null : rutaRubro(rubro);
            if (ruta != null) {
                return urlPagina(ruta, 1);
            }
            String pasillo = PASILLOS.get(palabra);
            if (pasillo != null) {
                String url = urlPasillo(pasillo, consulta);
                if (url != null) {
                    return url;
                }
            }
        }
        return null;
    }

    String rutaRubro(Rubro rubro) {
        return switch (this) {
            case JUMBO, SANTA_ISABEL -> switch (rubro) {
                case YOGUR -> "lacteos-huevos-y-congelados/yoghurt";
                case MANTEQUILLA -> "lacteos-huevos-y-congelados/mantequillas-y-margarinas/mantequillas";
                case CEREAL -> "despensa/cereales-avenas-y-barras/cereales";
                case AVENA -> "despensa/cereales-avenas-y-barras/avena-y-granola";
                case CAFE -> "despensa/cafe-y-cafeteras";
                case QUESO -> "quesos-y-fiambres/quesos";
                case FIAMBRE -> "quesos-y-fiambres/fiambres";
                case CONSERVA -> "despensa/conservas";
                case MERMELADA -> "despensa/mermeladas-miel-y-otros/mermeladas";
                case PAN -> "panaderia-y-pasteleria/panaderia-envasada";
                case HELADO -> this == JUMBO ? "lacteos-huevos-y-congelados/helados-y-postres" : null;
                case JUGO -> this == JUMBO ? "licores-bebidas-y-aguas/jugos" : "botilleria/jugos";
                case MASCOTAS -> "mascotas";
            };
            case LIDER -> switch (rubro) {
                case YOGUR -> "yogurt";
                case MANTEQUILLA -> "mantequilla";
                case CEREAL -> "cereales";
                case AVENA -> "avena";
                case CAFE -> "cafe";
                case PAN -> "panaderia";
                case HELADO -> "helados";
                case JUGO -> "jugos";
                case MASCOTAS -> "alimentos-para-perros";
                case QUESO -> "queso";
                case FIAMBRE -> "jamon";
                case CONSERVA -> "atun";
                case MERMELADA -> "mermelada";
            };
            case TOTTUS -> switch (rubro) {
                case YOGUR -> "CATG27182/Yoghurt";
                case MANTEQUILLA -> "CATG27185/Mantequillas-y-Mantecas";
                case CEREAL -> "CATG27115/Cereales";
                case AVENA -> "CATG27370/Avenas-y-Granolas";
                case CAFE -> "CATG27113/Cafe";
                case QUESO -> "CATG27180/Quesos";
                case FIAMBRE -> "CATG27518/Jamones--Chorizos-y-Fiambres";
                case CONSERVA -> "CATG27059/Conservas-y-Enlatados";
                case MERMELADA -> "CATG27119/Mermeladas-y-Untables";
                case PAN -> "CATG27140/Panaderia";
                case HELADO -> "CATG27129/Helados";
                case JUGO -> "CATG27216/Jugos-y-Nectar";
                case MASCOTAS -> "CATG27078/Mascotas";
            };
            case UNIMARC -> switch (rubro) {
                case YOGUR -> "lacteos-huevos-y-refrigerados/yoghurt";
                case MANTEQUILLA -> "lacteos-huevos-y-refrigerados/mantequillas/mantequilla";
                case CEREAL, AVENA -> "desayuno-y-dulces/cereales";
                case CAFE -> "desayuno-y-dulces/cafe-te-y-hierbas";
                case QUESO -> "quesos-y-fiambres/quesos";
                case FIAMBRE -> "quesos-y-fiambres/fiambres-y-embutidos";
                case CONSERVA -> "despensa/conservas";
                case MERMELADA -> "desayuno-y-dulces/mermelada-y-manjar";
                case PAN -> "panaderia-y-pasteleria";
                case HELADO -> "congelados/hielo-helados-y-postres";
                case JUGO -> "bebidas-y-licores/jugos";
                case MASCOTAS -> null;
            };
            case ALVI -> switch (rubro) {
                case YOGUR -> "lacteos-refrigerados-y-huevos/yoghurt";
                case MANTEQUILLA -> "lacteos-refrigerados-y-huevos/mantequillas";
                case CEREAL, AVENA -> "abarrotes/cereales";
                case CAFE -> "abarrotes/cafe";
                case QUESO -> "lacteos-refrigerados-y-huevos/quesos";
                case FIAMBRE -> "fiambreria/embutidos";
                case MERMELADA -> "panaderia-y-reposteria/manjar-y-mermeladas";
                case PAN -> "panaderia-y-reposteria";
                case HELADO -> "congelados/helados-y-hielo";
                case MASCOTAS -> "mascotas/alimentos-perros";
                case CONSERVA, JUGO -> null;
            };
            case CUGAT -> switch (rubro) {
                case YOGUR -> "lacteos/yoghurt-y-postres";
                case MANTEQUILLA -> "lacteos/mantequillas-y-margarinas";
                case CEREAL, AVENA -> "desayuno/cereales-y-saborizantes";
                case CAFE -> "desayuno/cafe-y-sucedaneos";
                case QUESO -> "fiambreria-embutidos-y-quesos/quesos";
                case FIAMBRE -> "fiambreria-embutidos-y-quesos/fiambreria-y-embutidos";
                case CONSERVA -> "despensa/conservas-y-enlatados";
                case MERMELADA -> "desayuno/mermeladas-y-dulces";
                case PAN -> "panaderia-y-pasteleria/envasados";
                case HELADO -> "mundo-congelados/helados";
                case JUGO -> "bebidas-jugos-y-aguas/jugos";
                case MASCOTAS -> "mascotas/alimento-de-mascotas";
            };
            case DEL_PEDREGAL -> null;
        };
    }

    String urlPagina(String ruta, int pagina) {
        String host = "https://" + URI.create(catalogo).getHost();
        String query = pagina == 1 ? "" : "?page=" + pagina;
        return switch (this) {
            case JUMBO, SANTA_ISABEL -> host + "/" + ruta + query;
            case TOTTUS -> host + "/tottus-cl/lista/" + ruta + query;
            case LIDER -> host + "/v/" + ruta + (pagina == 1 ? ""
                    : "?sortingorder=ascending&itemsperpage=48&display=grid&pagenumber=" + pagina);
            case UNIMARC -> host + "/category/" + ruta + query;
            case ALVI -> host + ("especial-almacenero".equals(ruta) ? "/ofertas/" : "/category/") + ruta + query;
            case CUGAT -> host + "/categoria-producto/" + ruta + "/" + (pagina == 1 ? "" : "page/" + pagina + "/");
            case DEL_PEDREGAL -> catalogo;
        };
    }

    private String urlPasillo(String pasillo, String consulta) {
        return switch (this) {
            case LIDER -> "leche".equals(pasillo) ? null : "https://super.lider.cl/v/" + pasillo;
            case TOTTUS -> {
                String ruta = switch (pasillo) {
                    case "arroz" -> "CATG27292/Arroz";
                    case "aceite" -> "CATG27056/Aceites";
                    case "pastas" -> "CATG27062/Pastas-y-Salsas";
                    case "huevos" -> "CATG27266/Huevos";
                    case "pollo" -> "CATG27092/Pollo";
                    case "detergente" -> consulta.matches(".*\\bpolvo\\b.*")
                            ? "CATG27440/Detergentes-en-Polvo" : "CATG27439/Detergentes-Liquidos";
                    default -> null;
                };
                yield ruta == null ? null : "https://www.tottus.cl/tottus-cl/lista/" + ruta;
            }
            case CUGAT -> {
                String ruta = switch (pasillo) {
                    case "arroz" -> "despensa/arroz-legumbres-y-semillas";
                    case "aceite" -> "despensa/aceites";
                    case "pastas" -> "despensa/pastas-fideos-y-salsas";
                    case "huevos" -> "despensa/huevos";
                    case "leche" -> "lacteos/leches-liquidas-y-cremas";
                    case "detergente" -> "limpieza-y-aseo/detergentes-y-suavizantes";
                    default -> null;
                };
                yield ruta == null ? null : "https://cugat.cl/categoria-producto/" + ruta + "/";
            }
            default -> null;
        };
    }

    boolean aceptaDominio(String host) {
        return dominio.equalsIgnoreCase(host)
                || (host != null && host.toLowerCase(java.util.Locale.ROOT).endsWith("." + dominio));
    }
}
