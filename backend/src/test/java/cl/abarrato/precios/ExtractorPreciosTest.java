package cl.abarrato.precios;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.OffsetDateTime;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

class ExtractorPreciosTest {
    private final ExtractorPrecios extractor = new ExtractorPrecios(new ObjectMapper());
    private final OffsetDateTime instante = OffsetDateTime.parse("2026-11-07T20:00:00Z");

    @Test
    void alviGuardaElPrecioUnitarioBaseSinAplicarEscalas() throws IOException {
        String html = """
                <script id="__NEXT_DATA__">{
                  "props":{"pageProps":{"dehydratedState":{"queries":[{"state":{"data":{"products":[{
                    "sku":"799","name":"Aceite vegetal 900 ml",
                    "images":["https://alvicl.vtexassets.com/arquivos/ids/12345/aceite.jpg"],
                    "sellers":[{"sellerId":"1","price":1640,"priceWithoutDiscount":1690,"availableQuantity":10}],
                    "priceSteps":[{"minQuantity":3,"promotionalPrice":1640}]
                  }]}}}]}}}
                }</script>
                """;
        var precios = extractor.extraer(Comercio.ALVI, Jsoup.parse(html), instante);
        assertThat(precios).singleElement().satisfies(precio -> {
            assertThat(precio.precioTexto()).isEqualTo("1690");
            assertThat(precio.precioClp()).isEqualTo(1690);
            assertThat(precio.urlImagen()).isEqualTo("https://alvicl.vtexassets.com/arquivos/ids/12345/aceite.jpg");
            // El precio por cantidad se guarda aparte, como oferta con su condición.
            assertThat(precio.precioOfertaClp()).isEqualTo(1640);
            assertThat(precio.condicionOferta()).isEqualTo("Llevando 3 o más");
            assertThat(precio.isEnOferta()).isTrue();
        });
    }

    @Test
    void tottusGuardaSoloElPrecioPublicoDelSupermercado() throws IOException {
        String tarjeta = """
                <a class="pod-link" data-key="11" id="testId-pod-12">
                  <b class="pod-subTitle">Leche entera</b><b class="pod-sellerText">Por Tottus</b>
                  <ol>
                    <li data-internet-price="1.190"><div class="cmr-icon-container"><span>$  1.190</span><span>UN</span></div></li>
                    <li data-normal-price="1.350"><span>$  1.350</span></li>
                  </ol>
                </a>
                """;
        String fichas = """
                <script id="__NEXT_DATA__">{"props":{"pageProps":{"results":[{
                  "skuId":"12","sellerId":"TOTTUS_CHILE",
                  "mediaUrls":["https://media.tottus.cl/tottusCL/123/public"],
                  "url":"https://www.tottus.cl/tottus-cl/articulo/11/leche-entera"
                }]}}}</script>
                """;
        String html = fichas + tarjeta + tarjeta.replace("Por Tottus", "Por tercero").replace("pod-12", "pod-13")
                + tarjeta.replace("pod-12", "pod-14").replace("<span>$  1.190", "<img><span>$  1.190")
                + tarjeta.replace("pod-12", "pod-15").replace("<span>UN", "<span>KG");
        var precios = extractor.extraer(Comercio.TOTTUS, Jsoup.parse(html), instante);
        assertThat(precios).singleElement().satisfies(precio -> {
            assertThat(precio.productoId()).isEqualTo("12");
            assertThat(precio.precioTexto()).isEqualTo("$  1.190");
            assertThat(precio.precioClp()).isEqualTo(1190);
            assertThat(precio.urlImagen()).isEqualTo("https://media.tottus.cl/tottusCL/123/public");
            assertThat(precio.urlFuente()).isEqualTo("https://www.tottus.cl/tottus-cl/articulo/11/leche-entera");
            assertThat(precio.precioAntesClp()).isEqualTo(1350);
            assertThat(precio.precioOfertaClp()).isNull();
        });
    }

    @Test
    void cugatGuardaElPrecioMinoristaYLaFotoDeProductosEnStock() throws IOException {
        String tarjeta = """
                <div class="product-small instock purchasable product-type-simple">
                  <a class="woocommerce-loop-product__link" href="https://cugat.cl/producto/arroz/">Arroz 1 kg</a>
                  <img data-src="https://cugat.cl/wp-content/uploads/arroz.webp">
                  <div class="trc-retail-info"><div class="trc-precios">
                    <ins><span class="woocommerce-Price-amount">$1.490</span></ins>
                    <del><span class="woocommerce-Price-amount">$1.690</span></del>
                  </div></div>
                  <a class="trc-agregar" data-product_id="90775" data-product_sku="5609040778884">Agregar</a>
                </div>
                """;
        String html = tarjeta + tarjeta.replace("instock", "outofstock").replace("90775", "90776");
        var precios = extractor.extraer(Comercio.CUGAT, Jsoup.parse(html), instante);
        assertThat(precios).singleElement().satisfies(precio -> {
            assertThat(precio.productoId()).isEqualTo("90775");
            assertThat(precio.precioClp()).isEqualTo(1490);
            assertThat(precio.claveComparacion()).isEqualTo("gtin:05609040778884");
            assertThat(precio.urlImagen()).isEqualTo("https://cugat.cl/wp-content/uploads/arroz.webp");
            assertThat(precio.precioAntesClp()).isEqualTo(1690);
        });
    }

    @Test
    void tottusIncluyeFichasDiferidasConPrecioPorUnidadSinCondiciones() throws IOException {
        String ficha = """
                {"skuId":"123","sellerId":"TOTTUS_CHILE","displayName":"Arroz 1 kg",
                 "url":"https://www.tottus.cl/tottus-cl/articulo/122/arroz",
                 "measurements":{"unit":"UN"},
                 "prices":[{"type":"internetPrice","crossed":false,"label":"","icons":"",
                            "symbol":"$ ","price":["1.490"]}]}
                """;
        String html = "<script id=\"__NEXT_DATA__\">{\"props\":{\"pageProps\":{\"results\":["
                + ficha + "," + ficha.replace("123", "124").replace("\"UN\"", "\"KG\"")
                + "," + ficha.replace("123", "125").replace("\"icons\":\"\"", "\"icons\":\"cmr\"")
                + "]}}}</script>";
        assertThat(extractor.extraer(Comercio.TOTTUS, Jsoup.parse(html), instante))
                .singleElement().satisfies(precio -> assertThat(precio.precioClp()).isEqualTo(1490));
    }

    @Test
    void tottusGuardaElPrecioConTarjetaCmrComoOferta() throws IOException {
        String ficha = """
                {"skuId":"126","sellerId":"TOTTUS_CHILE","displayName":"Aceite 1 L",
                 "url":"https://www.tottus.cl/tottus-cl/articulo/126/aceite",
                 "measurements":{"unit":"UN"},
                 "prices":[{"type":"internetPrice","crossed":false,"label":"","icons":"","symbol":"$ ","price":["2.990"]},
                           {"type":"cmrPrice","crossed":false,"label":"","icons":"cmr","symbol":"$ ","price":["2.490"]},
                           {"type":"normalPrice","crossed":true,"label":"","icons":"","symbol":"$ ","price":["3.290"]}]}
                """;
        String html = "<script id=\"__NEXT_DATA__\">{\"props\":{\"pageProps\":{\"results\":[" + ficha + "]}}}</script>";
        assertThat(extractor.extraer(Comercio.TOTTUS, Jsoup.parse(html), instante)).singleElement().satisfies(precio -> {
            assertThat(precio.precioClp()).isEqualTo(2990);
            assertThat(precio.precioAntesClp()).isEqualTo(3290);
            assertThat(precio.precioOfertaClp()).isEqualTo(2490);
            assertThat(precio.condicionOferta()).isEqualTo("Con tarjeta CMR");
        });
    }

    @Test
    void unaOfertaQueNoEsMasBarataSeDescarta() {
        var precio = new ObservacionPrecio(Comercio.JUMBO, "1", "ONLINE_SIN_UBICACION", "Arroz", "$1.000", 1000,
                "https://www.jumbo.cl/arroz/p", instante).conOferta(900, 1200, "Con tarjeta");
        assertThat(precio.precioAntesClp()).isNull();
        assertThat(precio.precioOfertaClp()).isNull();
        assertThat(precio.condicionOferta()).isNull();
        assertThat(precio.isEnOferta()).isFalse();
    }

    // ---------- Jumbo y Santa Isabel: promociones fuera del bloque de precios ----------

    private static String tarjetaJumbo(String id, String nombre, int precio, String precios) {
        return "<div><a href=\"/" + id + "/p\" data-gtm-product-click='{\"id\":\"" + id + "\",\"name\":\"" + nombre
                + "\",\"price\":" + precio + ",\"refId\":\"" + id + "\"}'><div class=\"principal-product-image\"></div></a>"
                + "<div><div class=\"plp_card\">" + precios + "</div></div></div>";
    }

    @Test
    void jumboGuardaElPrecioConTarjetaCencosudComoOferta() throws IOException {
        String html = tarjetaJumbo("1845786", "Arroz Grado 1 Cuisine & Co 1 kg", 1590,
                "<div class=\"items-baseline\">$1.590</div>"
                        + "<div class=\"flex\"><img alt=\"Paga $1.390\" src=\"https://www.jumbo.cl/tarjeta.svg\">"
                        + "<span>Paga $1.390</span></div>");
        var precios = extractor.extraer(Comercio.JUMBO, Jsoup.parse(html, "https://www.jumbo.cl/busqueda?ft=arroz"), instante);
        assertThat(precios).singleElement().satisfies(precio -> {
            assertThat(precio.precioClp()).isEqualTo(1590);
            assertThat(precio.precioOfertaClp()).isEqualTo(1390);
            assertThat(precio.condicionOferta()).isEqualTo("Con Tarjeta Cencosud");
        });
    }

    @Test
    void santaIsabelGuardaLaPromocionPorCantidadComoPrecioPorUnidad() throws IOException {
        String html = tarjetaJumbo("999", "Arroz Chaufan 210 g", 1230,
                "<button aria-label=\"Ver promoción combinable: Lleva 2 por $1.990\"><span>Lleva 2 por $1.990</span></button>"
                        + "<div class=\"items-baseline\">$1.230</div>");
        var precios = extractor.extraer(Comercio.SANTA_ISABEL,
                Jsoup.parse(html, "https://www.santaisabel.cl/busqueda?ft=arroz"), instante);
        assertThat(precios).singleElement().satisfies(precio -> {
            assertThat(precio.precioOfertaClp()).isEqualTo(995);
            assertThat(precio.condicionOferta()).isEqualTo("Lleva 2 por $1.990");
        });
    }

    @Test
    void unaPromocionMasCaraQueElPrecioNormalNoSeGuarda() throws IOException {
        String html = tarjetaJumbo("777", "Arroz Tucapel 1 kg", 2390,
                "<div class=\"items-baseline\">$2.390</div><span>Paga $2.590</span><span>Lleva 2 por $4.990</span>");
        var precios = extractor.extraer(Comercio.JUMBO, Jsoup.parse(html, "https://www.jumbo.cl/busqueda?ft=arroz"), instante);
        assertThat(precios).singleElement().satisfies(precio -> {
            assertThat(precio.precioOfertaClp()).isNull();
            assertThat(precio.condicionOferta()).isNull();
        });
    }
}
