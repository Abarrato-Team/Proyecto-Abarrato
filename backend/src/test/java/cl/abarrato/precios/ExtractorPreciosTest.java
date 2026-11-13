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
}
