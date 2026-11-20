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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

// Lee el HTML/JSON de cada supermercado y obtiene nombre, precio, foto y ofertas.
// Cada tienda tiene su propio método porque cada web es distinta.
@Component
class ExtractorPrecios {
    private static final String ONLINE = "ONLINE_SIN_UBICACION";
    private final ObjectMapper json;

    ExtractorPrecios(ObjectMapper json) {
        this.json = json;
    }

    // Extrae los precios de una página; si no encuentra ninguno, avisa con un error.
    List<ObservacionPrecio> extraer(Comercio comercio, Document pagina, OffsetDateTime instante) throws IOException {
        List<ObservacionPrecio> precios = extraerPagina(comercio, pagina, instante);
        if (precios.isEmpty()) {
            throw new IOException("El catálogo no contiene precios reconocibles");
        }
        return precios;
    }

    // Elige el lector según la tienda.
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

    // Jumbo y Santa Isabel (plataforma Cencosud): precio normal y con tarjeta.
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
            var montos = detalle.select(".plp_card div.items-baseline");
            for (Element precio : montos) {
                String texto = precio.ownText();
                if (monto > 0 && monto(texto) == monto) {
                    // Otros precios de la tarjeta: uno más alto es el normal tachado; uno más bajo,
                    // un precio especial (por ejemplo, con Tarjeta Cencosud).
                    Integer antes = null;
                    Integer oferta = null;
                    String condicion = null;
                    for (Element otro : montos) {
                        int valor = monto(otro.ownText());
                        if (valor > monto && (antes == null || valor > antes)) {
                            antes = valor;
                        } else if (valor > 0 && valor < monto && (oferta == null || valor < oferta)) {
                            oferta = valor;
                            String contexto = otro.parent() == null ? "" : otro.parent().text().toLowerCase();
                            condicion = contexto.contains("tarjeta") ? "Con Tarjeta Cencosud" : "Precio especial";
                        }
                    }
                    // Promociones que Jumbo y Santa Isabel muestran fuera del bloque de precios:
                    // "Paga $1.390" junto al logo de la Tarjeta Cencosud, o "Lleva 2 por $1.990".
                    Promocion promocion = promocionCencosud(detalle, monto);
                    if (promocion != null && (oferta == null || promocion.precio() <= oferta)) {
                        oferta = promocion.precio();
                        condicion = promocion.condicion();
                    }
                    precios.add(observacion(comercio, producto.path("id").asText(), producto.path("name").asText(),
                            texto, monto, URI.create(comercio.catalogo()).resolve(enlace.attr("href")).toString(),
                            instante, clave, ImagenProducto.enTarjeta(enlace)).conOferta(antes, oferta, condicion));
                    break;
                }
            }
        }
        return precios;
    }

    /** Precio especial "Paga $X" (Tarjeta Cencosud) en la tarjeta de un producto de Jumbo o Santa Isabel. */
    private static final Pattern PAGA = Pattern.compile("(?i)\\bpaga\\s*\\$\\s*([0-9]{1,3}(?:\\.[0-9]{3})+|[0-9]{1,7})");
    /** Promoción por cantidad: "Lleva 2 por $1.990" (también "Lleva 3 x $5.000"). */
    private static final Pattern LLEVA = Pattern.compile(
            "(?i)\\blleva\\s*([0-9]{1,2})\\s*(?:un\\.?|unidades)?\\s*(?:por|x)\\s*\\$\\s*([0-9]{1,3}(?:\\.[0-9]{3})+|[0-9]{1,7})");

    /** Promoción encontrada: precio por unidad y condición que ve el usuario. */
    record Promocion(int precio, String condicion) {}

    /**
     * Busca en la tarjeta del producto (textos, texto alternativo de imágenes y etiquetas accesibles)
     * el precio con Tarjeta Cencosud o una promoción por cantidad. Devuelve la más barata que sea menor
     * que el precio normal, o null si no hay.
     */
    static Promocion promocionCencosud(Element tarjeta, int precioNormal) {
        Promocion mejor = null;
        for (Element elemento : tarjeta.getAllElements()) {
            for (String texto : new String[] {elemento.ownText(), elemento.attr("alt"), elemento.attr("aria-label"),
                    elemento.attr("title")}) {
                if (texto.isEmpty()) {
                    continue;
                }
                Matcher paga = PAGA.matcher(texto);
                if (paga.find()) {
                    mejor = masBarata(mejor, new Promocion(digitos(paga.group(1)), "Con Tarjeta Cencosud"), precioNormal);
                }
                Matcher lleva = LLEVA.matcher(texto);
                if (lleva.find()) {
                    int unidades = Integer.parseInt(lleva.group(1));
                    int total = digitos(lleva.group(2));
                    if (unidades >= 2) {
                        mejor = masBarata(mejor, new Promocion(Math.round((float) total / unidades),
                                "Lleva " + unidades + " por " + pesos(total)), precioNormal);
                    }
                }
            }
        }
        return mejor;
    }

    private static Promocion masBarata(Promocion actual, Promocion nueva, int precioNormal) {
        if (nueva.precio() <= 0 || nueva.precio() >= precioNormal) {
            return actual;
        }
        return actual == null || nueva.precio() < actual.precio() ? nueva : actual;
    }

    private static int digitos(String numero) {
        String limpio = numero.replace(".", "");
        return limpio.isEmpty() || limpio.length() > 7 ? 0 : Integer.parseInt(limpio);
    }

    // Tottus: precio normal, precio CMR y fichas que se cargan después.
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
                    Element normal = tarjeta.selectFirst("li[data-normal-price]");
                    Element cmr = tarjeta.selectFirst("li[data-cmr-price]");
                    Integer antes = normal == null ? null : positivo(monto("$" + normal.attr("data-normal-price")));
                    Integer oferta = cmr == null ? null : positivo(monto("$" + cmr.attr("data-cmr-price")));
                    precios.add(observacion(Comercio.TOTTUS, id, nombre, texto, monto,
                            fuente, instante, clave, imagen).conOferta(antes, oferta, "Con tarjeta CMR"));
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

    // Tottus: lee una ficha de producto incluida en los datos diferidos.
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
                Integer antes = null;
                Integer oferta = null;
                for (JsonNode otro : ficha.path("prices")) {
                    if (otro.path("price").size() != 1) {
                        continue;
                    }
                    Integer valor = positivo(monto("$" + otro.path("price").path(0).asText()));
                    String tipo = otro.path("type").asText();
                    if ("cmrPrice".equals(tipo)) {
                        oferta = valor;
                    } else if ("normalPrice".equals(tipo) || otro.path("crossed").asBoolean()) {
                        antes = valor;
                    }
                }
                return Optional.of(observacion(Comercio.TOTTUS, id, nombre, texto, monto, fuente, instante, clave,
                        ImagenProducto.validar(ficha.path("mediaUrls").path(0).asText()))
                        .conOferta(antes, oferta, "Con tarjeta CMR"));
            }
        }
        return Optional.empty();
    }

    // Tottus: arma la URL de la ficha del producto.
    private static String urlFichaTottus(JsonNode ficha) {
        String url = ficha.path("url").asText();
        return url.matches("https://www\\.tottus\\.cl/tottus-cl/articulo/[0-9]{1,24}/[a-zA-Z0-9%_-]{1,200}")
                ? url : null;
    }

    // Tottus: obtiene las fichas diferidas del HTML.
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
                JsonNode antesNodo = producto.path("priceInfo").path("wasPrice");
                String antesTexto = antesNodo.isTextual() ? antesNodo.asText() : antesNodo.path("priceString").asText();
                precios.add(observacion(Comercio.LIDER, id, nombre, texto, monto, "https://www.lider.cl" + ruta,
                        instante, IdentidadProducto.codigoBarras(id),
                        ImagenProducto.validar(producto.path("imageInfo").path("thumbnailUrl").asText()))
                        .conOferta(antesTexto.isBlank() ? null : positivo(monto(antesTexto)), null, null));
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
                Integer antes = null;
                try {
                    String comparar = variante.path("compare_at_price").asText();
                    antes = comparar.isBlank() || "null".equals(comparar) ? null
                            : positivo((int) Math.round(Double.parseDouble(comparar)));
                } catch (NumberFormatException error) {
                    antes = null;
                }
                precios.add(observacion(Comercio.DEL_PEDREGAL, handle, nombre, pesos(monto), monto,
                        "https://alimentosdelpedregal.com/products/" + handle, instante, null, imagen)
                        .conOferta(antes, null, null));
                break;
            }
        }
        return precios;
    }

    // Formatea un monto como texto en pesos: $1.290.
    private static String pesos(int monto) {
        return "$" + String.format(java.util.Locale.ROOT, "%,d", monto).replace(',', '.');
    }

    // Líder: precio y precio anterior (wasPrice).
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

    // Cugat: precio y precio tachado.
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
                Element normal = tarjeta.selectFirst(".trc-retail-info .trc-precios del .woocommerce-Price-amount");
                precios.add(observacion(Comercio.CUGAT, compra.attr("data-product_id"), enlace.text(),
                        texto, monto, enlace.attr("href"), instante,
                        IdentidadProducto.codigoBarras(compra.attr("data-product_sku")),
                        ImagenProducto.enTarjeta(tarjeta))
                        .conOferta(normal == null ? null : positivo(monto(normal.text())), null, null));
            }
        }
        return precios;
    }

    // Alvi: precio y descuento por cantidad ("Llevando N o más").
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
                        // Precio rebajado: por cantidad (escalas) o una oferta del día.
                        Integer oferta = vendedor.path("price").canConvertToInt()
                                ? positivo(vendedor.path("price").asInt()) : null;
                        String condicion = "Precio oferta";
                        for (JsonNode escala : producto.path("priceSteps")) {
                            if (oferta != null && escala.path("promotionalPrice").asInt() == oferta) {
                                condicion = "Llevando " + escala.path("minQuantity").asInt() + " o más";
                            }
                        }
                        precios.add(observacion(Comercio.ALVI, producto.path("sku").asText(),
                                producto.path("name").asText(), precio.asText(), precio.asInt(),
                                fuente, instante, null,
                                ImagenProducto.validar(producto.path("images").path(0).asText()))
                                .conOferta(null, oferta, condicion));
                    }
                }
            }
        }
        return precios;
    }

    // Unimarc: precio, precio de lista y promociones (Club Unimarc, medio de pago).
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
                    JsonNode vendedor = vendedores.path(0);
                    Integer oferta = null;
                    String motivo = null;
                    if (condicion) {
                        oferta = vendedor.path("price").canConvertToInt() ? positivo(vendedor.path("price").asInt()) : null;
                        motivo = vendedor.path("rewardValue").asInt() > 0 ? "Con Club Unimarc"
                                : !producto.path("priceDetail").path("pricePaymentsMethods").isEmpty()
                                ? "Con medio de pago" : "Promoción";
                    }
                    Integer antes = vendedor.path("listPrice").canConvertToInt()
                            ? positivo(vendedor.path("listPrice").asInt()) : null;
                    precios.add(observacion(Comercio.UNIMARC, producto.path("sku").asText(),
                            producto.path("name").asText(), precio.asText(), precio.asInt(), fuente,
                            instante, IdentidadProducto.codigoBarras(producto.path("ean").asText()),
                            ImagenProducto.validar(producto.path("images").path(0).asText()))
                            .conOferta(antes, oferta, motivo));
                }
            }
        }
        return precios;
    }

    // Líder: lee una ficha individual de producto (se usa para la leche de referencia).
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

    // Crea la observación con los datos comunes.
    private static ObservacionPrecio observacion(Comercio comercio, String id, String nombre, String texto,
            int monto, String fuente, OffsetDateTime instante, String clave, String imagen) {
        if (id.isBlank() || id.length() > 120 || nombre.isBlank()) {
            throw new IllegalArgumentException("La ficha no tiene un identificador o nombre válido");
        }
        return new ObservacionPrecio(comercio, id, ONLINE, nombre, texto, monto, fuente, instante, clave, imagen);
    }

    // Devuelve el monto solo si es mayor que cero.
    private static Integer positivo(int valor) {
        return valor > 0 ? valor : null;
    }

    // Convierte un texto de precio en pesos enteros.
    private static int monto(String texto) {
        try {
            return PrecioClp.normalizar(texto.replaceAll("\\h+", " "));
        } catch (IllegalArgumentException ex) {
            return 0;
        }
    }
}
