package cl.abarrato.precios;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

@Component
class ExtractorPrecios {
    private static final String ONLINE = "ONLINE_SIN_UBICACION";
    private final ObjectMapper json;

    ExtractorPrecios(ObjectMapper json) {
        this.json = json;
    }

    List<ObservacionPrecio> extraer(Comercio comercio, Document pagina, OffsetDateTime instante) throws IOException {
        List<ObservacionPrecio> precios = extraerPagina(comercio, pagina, instante);
        if (precios.isEmpty()) {
            throw new IOException("El catálogo no contiene precios reconocibles");
        }
        return precios;
    }

    List<ObservacionPrecio> extraerPagina(Comercio comercio, Document pagina, OffsetDateTime instante)
            throws IOException {
        List<ObservacionPrecio> candidatos = switch (comercio) {
            case DEL_PEDREGAL -> RecolectorPedregal.extraer(pagina, instante);
            case JUMBO, SANTA_ISABEL -> cencosud(comercio, pagina, instante);
            case TOTTUS -> tottus(pagina, instante);
            case LIDER -> {
                List<ObservacionPrecio> busqueda = liderBusqueda(pagina, instante);
                yield busqueda.isEmpty() ? lider(pagina, instante) : busqueda;
            }
            case ALVI -> alvi(pagina, instante);
            case UNIMARC -> unimarc(pagina, instante);
            case CUGAT -> cugat(pagina, instante);
        };
        Map<String, ObservacionPrecio> precios = new LinkedHashMap<>();
        for (ObservacionPrecio candidato : candidatos) {
            ObservacionPrecio anterior = precios.putIfAbsent(candidato.productoId(), candidato);
            if (anterior != null && anterior.precioClp() != candidato.precioClp()) {
                throw new IOException("El catálogo contiene precios incompatibles para un producto");
            }
        }
        return List.copyOf(precios.values());
    }

    private List<ObservacionPrecio> cencosud(Comercio comercio, Document pagina, OffsetDateTime instante)
            throws IOException {
        List<ObservacionPrecio> precios = new ArrayList<>();
        for (Element enlace : pagina.select("a[data-gtm-product-click]:has(.principal-product-image)")) {
            if (!enlace.attr("href").matches("/[^/?#]+/p")) {
                continue;
            }
            Element detalle = enlace.nextElementSibling();
            if (detalle == null) {
                continue;
            }
            JsonNode producto = json.readTree(enlace.attr("data-gtm-product-click"));
            int monto = producto.path("price").asInt();
            String referencia = producto.path("refId").asText();
            String clave = "1589277".equals(referencia)
                    && "/leche-soprole-natural-1-litro/p".equals(enlace.attr("href"))
                    ? IdentidadProducto.SOPROLE_ENTERA_1_L
                    : referencia.matches("[0-9]{1,24}") ? "cencosud:" + referencia : null;
            for (Element precio : detalle.select(".plp_card div.items-baseline")) {
                String texto = precio.ownText();
                if (monto > 0 && monto(texto) == monto) {
                    precios.add(observacion(comercio, producto.path("id").asText(), producto.path("name").asText(),
                            texto, monto, URI.create(comercio.catalogo()).resolve(enlace.attr("href")).toString(),
                            instante, clave, ImagenProducto.enTarjeta(enlace)));
                    break;
                }
            }
        }
        return precios;
    }

    private List<ObservacionPrecio> tottus(Document pagina, OffsetDateTime instante) {
        List<ObservacionPrecio> precios = new ArrayList<>();
        String urlCategoria = pagina.location().isBlank() ? Comercio.TOTTUS.catalogo() : pagina.location();
        Map<String, JsonNode> fichas = fichasTottus(pagina);
        var visibles = new HashSet<String>();
        for (Element tarjeta : pagina.select("a.pod-link[data-key]:has(.pod-subTitle)")) {
            if (!tarjeta.select(".pod-sellerText").text().equalsIgnoreCase("Por Tottus")) {
                continue;
            }
            String id = tarjeta.attr("id").replaceFirst("^testId-pod-", "");
            visibles.add(id);
            Element internet = tarjeta.selectFirst("li[data-internet-price]");
            if (internet == null || internet.selectFirst("img, svg") != null) {
                continue;
            }
            var valores = internet.select(".cmr-icon-container > span");
            if (valores.size() >= 2 && "UN".equals(valores.get(1).text())) {
                Element precio = valores.first();
                String texto = precio.wholeText().strip();
                int monto = monto(texto);
                if (monto > 0 && monto == monto("$" + internet.attr("data-internet-price"))) {
                    String nombre = tarjeta.select(".pod-subTitle").text();
                    String clave = "112737943".equals(id) && nombre.equalsIgnoreCase("Leche Entera Natural Soprole 1 lt")
                            ? IdentidadProducto.SOPROLE_ENTERA_1_L : null;
                    String imagen = ImagenProducto.enTarjeta(tarjeta);
                    String fuente = urlCategoria;
                    JsonNode ficha = fichas.get(id);
                    if (ficha != null) {
                        if (imagen == null) {
                            imagen = ImagenProducto.validar(ficha.path("mediaUrls").path(0).asText());
                        }
                        String url = urlFichaTottus(ficha);
                        if (url != null) {
                            fuente = url;
                        }
                    }
                    precios.add(observacion(Comercio.TOTTUS, id, nombre, texto, monto,
                            fuente, instante, clave, imagen));
                }
            }
        }
        for (var entrada : fichas.entrySet()) {
            if (!visibles.contains(entrada.getKey())) {
                fichaDiferidaTottus(entrada.getKey(), entrada.getValue(), instante).ifPresent(precios::add);
            }
        }
        return precios;
    }

    private Optional<ObservacionPrecio> fichaDiferidaTottus(String id, JsonNode ficha, OffsetDateTime instante) {
        String fuente = urlFichaTottus(ficha);
        String nombre = ficha.path("displayName").asText().strip();
        if (fuente == null || nombre.isEmpty() || !id.matches("[0-9]{1,24}")
                || !"UN".equals(ficha.at("/measurements/unit").asText())) {
            return Optional.empty();
        }
        for (JsonNode precio : ficha.path("prices")) {
            if (!"internetPrice".equals(precio.path("type").asText())
                    || !precio.path("crossed").isBoolean() || precio.path("crossed").asBoolean()
                    || !precio.path("icons").asText().isBlank() || !precio.path("label").asText().isBlank()
                    || !"$".equals(precio.path("symbol").asText().strip()) || precio.path("price").size() != 1) {
                continue;
            }
            String texto = "$" + precio.path("price").path(0).asText();
            int monto = monto(texto);
            if (monto > 0) {
                String clave = "112737943".equals(id) && nombre.equalsIgnoreCase("Leche Entera Natural Soprole 1 lt")
                        ? IdentidadProducto.SOPROLE_ENTERA_1_L : null;
                return Optional.of(observacion(Comercio.TOTTUS, id, nombre, texto, monto, fuente, instante, clave,
                        ImagenProducto.validar(ficha.path("mediaUrls").path(0).asText())));
            }
        }
        return Optional.empty();
    }

    private static String urlFichaTottus(JsonNode ficha) {
        String url = ficha.path("url").asText();
        return url.matches("https://www\\.tottus\\.cl/tottus-cl/articulo/[0-9]{1,24}/[a-zA-Z0-9%_-]{1,200}")
                ? url : null;
    }

    private Map<String, JsonNode> fichasTottus(Document pagina) {
        Element datos = pagina.selectFirst("script#__NEXT_DATA__");
        if (datos == null) {
            return Map.of();
        }
        Map<String, JsonNode> fichas = new LinkedHashMap<>();
        try {
            for (JsonNode ficha : json.readTree(datos.data()).at("/props/pageProps/results")) {
                if ("TOTTUS_CHILE".equals(ficha.path("sellerId").asText())) {
                    fichas.putIfAbsent(ficha.path("skuId").asText(), ficha);
                }
            }
        } catch (JsonProcessingException error) {
            // Las tarjetas visibles siguen teniendo sus propios precios.
        }
        return fichas;
    }

    /** Resultados del buscador www.lider.cl: props.pageProps.initialData.searchResult.itemStacks[].items[]. */
    private List<ObservacionPrecio> liderBusqueda(Document pagina, OffsetDateTime instante) throws IOException {
        Element datos = pagina.selectFirst("script#__NEXT_DATA__");
        if (datos == null) {
            return List.of();
        }
        List<ObservacionPrecio> precios = new ArrayList<>();
        JsonNode pilas = json.readTree(datos.data()).at("/props/pageProps/initialData/searchResult/itemStacks");
        for (JsonNode pila : pilas) {
            for (JsonNode producto : pila.path("items")) {
                String id = producto.path("usItemId").asText(producto.path("id").asText());
                String nombre = producto.path("name").asText().strip();
                String ruta = producto.path("canonicalUrl").asText();
                String disponibilidad = producto.path("availabilityStatusV2").path("value").asText();
                String texto = producto.path("priceInfo").path("linePrice").asText();
                if (texto.isBlank()) {
                    texto = producto.path("priceInfo").path("itemPrice").asText();
                }
                int monto = monto(texto);
                if (monto <= 0 || nombre.isEmpty() || !id.matches("[A-Za-z0-9]{1,40}")
                        || !ruta.matches("/ip/[^\\s?#]{1,300}")
                        || (!disponibilidad.isEmpty() && !"IN_STOCK".equals(disponibilidad))) {
                    continue;
                }
                precios.add(observacion(Comercio.LIDER, id, nombre, texto, monto, "https://www.lider.cl" + ruta,
                        instante, IdentidadProducto.codigoBarras(id),
                        ImagenProducto.validar(producto.path("imageInfo").path("thumbnailUrl").asText())));
            }
        }
        return precios;
    }

    /** Catálogo JSON de Shopify de Del Pedregal: products[].variants[].price. */
    List<ObservacionPrecio> pedregalJson(String texto, OffsetDateTime instante) throws IOException {
        List<ObservacionPrecio> precios = new ArrayList<>();
        for (JsonNode producto : json.readTree(texto).path("products")) {
            String handle = producto.path("handle").asText();
            String nombre = producto.path("title").asText().strip();
            if (!handle.matches("[a-z0-9-]{1,120}") || nombre.isEmpty()) {
                continue;
            }
            for (JsonNode variante : producto.path("variants")) {
                if (!variante.path("available").asBoolean(false)) {
                    continue;
                }
                int monto;
                try {
                    monto = (int) Math.round(Double.parseDouble(variante.path("price").asText()));
                } catch (NumberFormatException error) {
                    continue;
                }
                if (monto <= 0) {
                    continue;
                }
                String imagen = ImagenProducto.validar(producto.path("images").path(0).path("src").asText());
                precios.add(observacion(Comercio.DEL_PEDREGAL, handle, nombre, pesos(monto), monto,
                        "https://alimentosdelpedregal.com/products/" + handle, instante, null, imagen));
                break;
            }
        }
        return precios;
    }

    private static String pesos(int monto) {
        return "$" + String.format(java.util.Locale.ROOT, "%,d", monto).replace(',', '.');
    }

    private List<ObservacionPrecio> lider(Document pagina, OffsetDateTime instante) {
        List<ObservacionPrecio> precios = new ArrayList<>();
        for (Element tarjeta : pagina.select(".product-card__wrapper[data-id]")) {
            Element enlace = tarjeta.selectFirst("a.product-card");
            Element precio = tarjeta.selectFirst(".product-card__price .normal-price");
            if (enlace == null || precio == null) {
                continue;
            }
            URI ficha = URI.create(Comercio.LIDER.catalogo()).resolve(enlace.attr("href"));
            if (!"https".equals(ficha.getScheme()) || !"super.lider.cl".equals(ficha.getHost())
                    || !ficha.getPath().startsWith("/ip/")) {
                continue;
            }
            String texto = precio.text();
            int monto = monto(texto);
            if (monto > 0) {
                precios.add(observacion(Comercio.LIDER, tarjeta.attr("data-id"), enlace.select("h2").text(), texto,
                        monto, "https://super.lider.cl" + ficha.getRawPath(), instante,
                        null, ImagenProducto.enTarjeta(tarjeta)));
            }
        }
        return precios;
    }

    private List<ObservacionPrecio> cugat(Document pagina, OffsetDateTime instante) {
        List<ObservacionPrecio> precios = new ArrayList<>();
        for (Element tarjeta : pagina.select(".product-small.instock.purchasable.product-type-simple")) {
            Element enlace = tarjeta.selectFirst("a.woocommerce-loop-product__link[href]");
            Element compra = tarjeta.selectFirst("a.trc-agregar[data-product_id]");
            var importes = tarjeta.select(".trc-retail-info .trc-precios ins > .woocommerce-Price-amount");
            if (enlace == null || compra == null || importes.size() != 1
                    || !enlace.attr("href").matches("https://cugat\\.cl/producto/[a-z0-9-]{1,180}/")
                    || !compra.attr("data-product_id").matches("[0-9]{1,20}")) {
                continue;
            }
            String texto = importes.first().text();
            int monto = monto(texto);
            if (monto > 0 && !enlace.text().isBlank()) {
                precios.add(observacion(Comercio.CUGAT, compra.attr("data-product_id"), enlace.text(),
                        texto, monto, enlace.attr("href"), instante,
                        IdentidadProducto.codigoBarras(compra.attr("data-product_sku")),
                        ImagenProducto.enTarjeta(tarjeta)));
            }
        }
        return precios;
    }

    private List<ObservacionPrecio> alvi(Document pagina, OffsetDateTime instante) throws IOException {
        List<ObservacionPrecio> precios = new ArrayList<>();
        Element datos = pagina.selectFirst("script#__NEXT_DATA__");
        if (datos == null) {
            return precios;
        }
        JsonNode consultas = json.readTree(datos.data()).at("/props/pageProps/dehydratedState/queries");
        for (JsonNode consulta : consultas) {
            JsonNode productos = consulta.at("/state/data/availableProducts");
            for (JsonNode producto : productos.isArray() ? productos : consulta.at("/state/data/products")) {
                if ("kg".equalsIgnoreCase(producto.path("measurementUnit").asText())) {
                    continue;
                }
                for (JsonNode vendedor : producto.path("sellers")) {
                    JsonNode precio = vendedor.path("priceWithoutDiscount");
                    if ("1".equals(vendedor.path("sellerId").asText()) && precio.isIntegralNumber()
                            && precio.canConvertToInt() && precio.asInt() > 0
                            && vendedor.path("availableQuantity").asInt() > 0) {
                        String detalle = producto.path("detailUrl").asText();
                        String fuente = detalle.matches("/[a-z0-9-]{1,180}/p")
                                ? "https://www.alvi.cl" + detalle
                                : pagina.location().isBlank() ? Comercio.ALVI.catalogo() : pagina.location();
                        precios.add(observacion(Comercio.ALVI, producto.path("sku").asText(),
                                producto.path("name").asText(), precio.asText(), precio.asInt(),
                                fuente, instante, null,
                                ImagenProducto.validar(producto.path("images").path(0).asText())));
                    }
                }
            }
        }
        return precios;
    }

    private List<ObservacionPrecio> unimarc(Document pagina, OffsetDateTime instante) throws IOException {
        Element datos = pagina.selectFirst("script#__NEXT_DATA__");
        if (datos == null) {
            return List.of();
        }
        List<ObservacionPrecio> precios = new ArrayList<>();
        JsonNode consultas = json.readTree(datos.data()).at("/props/pageProps/dehydratedState/queries");
        for (JsonNode consulta : consultas) {
            for (JsonNode producto : consulta.at("/state/data/availableProducts")) {
                JsonNode vendedores = producto.path("sellers");
                if (vendedores.size() != 1 || vendedores.path(0).path("availableQuantity").asInt() <= 0
                        || "kg".equalsIgnoreCase(producto.path("measurementUnit").asText())) {
                    continue;
                }
                // Se usa el precio base cuando el catálogo indica beneficios o promociones adicionales.
                boolean condicion = producto.path("promotion").path("hasSavings").asBoolean()
                        || !producto.path("priceDetail").path("pricePaymentsMethods").isEmpty()
                        || vendedores.path(0).path("rewardValue").asInt() > 0;
                JsonNode precio = vendedores.path(0).path(condicion ? "priceWithoutDiscount" : "price");
                if (precio.isIntegralNumber() && precio.canConvertToInt() && precio.asInt() > 0) {
                    String slug = producto.path("slug").asText();
                    String fuente = slug.matches("[a-z0-9-]{1,160}")
                            ? "https://www.unimarc.cl/product/" + slug : Comercio.UNIMARC.catalogo();
                    precios.add(observacion(Comercio.UNIMARC, producto.path("sku").asText(),
                            producto.path("name").asText(), precio.asText(), precio.asInt(), fuente,
                            instante, IdentidadProducto.codigoBarras(producto.path("ean").asText()),
                            ImagenProducto.validar(producto.path("images").path(0).asText())));
                }
            }
        }
        return precios;
    }

    Optional<ObservacionPrecio> lecheLider(Document pagina, String fuente, OffsetDateTime instante) throws IOException {
        for (Element script : pagina.select("script[type=application/ld+json]")) {
            JsonNode producto = json.readTree(script.data());
            if (producto == null || !"Product".equals(producto.path("@type").asText())
                    || !"00780290000130".equals(producto.path("sku").asText())
                    || !"Soprole".equalsIgnoreCase(producto.path("brand").path("name").asText())
                    || !producto.path("name").asText().replaceAll("\\s+", " ").equalsIgnoreCase("Leche Entera Natural, 1 L")) {
                continue;
            }
            JsonNode ofertas = producto.path("offers");
            for (JsonNode oferta : ofertas.isArray() ? ofertas : List.of(ofertas)) {
                JsonNode monto = oferta.path("price");
                if ("CLP".equals(oferta.path("priceCurrency").asText())
                        && oferta.path("availability").asText().endsWith("/InStock")
                        && monto.isIntegralNumber() && monto.canConvertToInt() && monto.asInt() > 0) {
                    return Optional.of(observacion(Comercio.LIDER, producto.path("sku").asText(),
                            "Leche Soprole Entera Natural 1 L", monto.asText(), monto.asInt(),
                            fuente, instante, IdentidadProducto.SOPROLE_ENTERA_1_L,
                            ImagenProducto.validar(producto.path("image").asText())));
                }
            }
        }
        return Optional.empty();
    }

    private static ObservacionPrecio observacion(Comercio comercio, String id, String nombre, String texto,
            int monto, String fuente, OffsetDateTime instante, String clave, String imagen) {
        if (id.isBlank() || id.length() > 120 || nombre.isBlank()) {
            throw new IllegalArgumentException("La ficha no tiene un identificador o nombre válido");
        }
        return new ObservacionPrecio(comercio, id, ONLINE, nombre, texto, monto, fuente, instante, clave, imagen);
    }

    private static int monto(String texto) {
        try {
            return PrecioClp.normalizar(texto.replaceAll("\\h+", " "));
        } catch (IllegalArgumentException ex) {
            return 0;
        }
    }
}
