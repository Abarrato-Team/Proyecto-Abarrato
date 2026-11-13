package cl.abarrato.precios;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

final class RecolectorPedregal {
    private static final String SITIO = "https://alimentosdelpedregal.com";
    private static final Pattern FICHA = Pattern.compile("/products/([a-z0-9-]{1,120})");

    private RecolectorPedregal() {
    }

    static List<ObservacionPrecio> extraer(Document documento, OffsetDateTime observadoEn) throws IOException {
        Map<String, ObservacionPrecio> observaciones = new LinkedHashMap<>();
        for (Element tarjeta : documento.select("a.hp-product-card[href]")) {
            var ficha = FICHA.matcher(tarjeta.attr("href"));
            var nombres = tarjeta.select(".hp-product-title");
            var precios = tarjeta.select(".hp-product-price");
            if (!ficha.matches() || nombres.size() != 1 || precios.size() != 1 || nombres.first().text().isBlank()) {
                continue;
            }
            String id = ficha.group(1);
            String precioTexto = precios.first().wholeText().strip();
            int precioClp;
            try {
                precioClp = PrecioClp.normalizar(precioTexto);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            ObservacionPrecio observacion = new ObservacionPrecio(Comercio.DEL_PEDREGAL, id,
                    "ONLINE_SIN_UBICACION", nombres.first().text(), precioTexto, precioClp, SITIO + ficha.group(),
                    observadoEn, null, ImagenProducto.enTarjeta(tarjeta));
            ObservacionPrecio anterior = observaciones.putIfAbsent(id, observacion);
            if (anterior != null && anterior.precioClp() != precioClp) {
                throw new IOException("La portada publicó precios incompatibles para el mismo producto");
            }
        }
        if (observaciones.isEmpty()) {
            throw new IOException("La portada de Del Pedregal no contiene precios reconocibles");
        }
        return List.copyOf(observaciones.values());
    }
}
