package cl.abarrato.precios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.OffsetDateTime;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

class RecolectorPedregalTest {
    private static final OffsetDateTime INSTANTE = OffsetDateTime.parse("2026-11-07T20:00:00Z");

    @Test
    void conservaElPrecioPublicadoYFiltraTarjetasInvalidas() throws IOException {
        String tarjeta = """
                <a class="hp-product-card" href="/products/pollo">
                  <p class="hp-product-title">Pollo empanado 300 g</p>
                  <p class="hp-product-price">$2.390</p>
                </a>
                """;
        String html = tarjeta + tarjeta + tarjeta.replace("$2.390", "2 por $2.390").replace("/pollo", "/promo")
                + tarjeta.replace("/products/pollo", "https://otro.example/products/pollo");
        var observaciones = RecolectorPedregal.extraer(Jsoup.parse(html), INSTANTE);
        assertThat(observaciones).containsExactly(new ObservacionPrecio(Comercio.DEL_PEDREGAL,
                "pollo", "ONLINE_SIN_UBICACION", "Pollo empanado 300 g", "$2.390", 2390,
                "https://alimentosdelpedregal.com/products/pollo", INSTANTE));
    }

    @Test
    void reportaUnaPortadaSinPreciosReconocibles() {
        assertThatThrownBy(() -> RecolectorPedregal.extraer(Jsoup.parse("<h1>Catálogo</h1>"), INSTANTE))
                .isInstanceOf(IOException.class);
    }
}
