package cl.abarrato.precios;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import org.jsoup.nodes.Element;

// Obtiene y valida la URL de la foto de un producto (solo https).
final class ImagenProducto {
    private static final Set<String> CDN = Set.of(
            "jumbocl.vteximg.com.br", "santaisabel.vteximg.com.br", "media.tottus.cl",
            "i5.walmartimages.cl", "i5.walmartimages.com", "unimarc.vtexassets.com", "alvicl.vteximg.com.br", "alvicl.vtexassets.com",
            "alimentosdelpedregal.com", "cdn.shopify.com", "cugat.cl");

    private ImagenProducto() {}

    // Busca la foto dentro de la tarjeta HTML de un producto.
    static String enTarjeta(Element tarjeta) {
        for (Element imagen : tarjeta.select("img")) {
            String url = validar(imagen.attr("data-src"));
            if (url == null) {
                url = validar(imagen.attr("src"));
            }
            if (url != null) {
                return url;
            }
        }
        return null;
    }

    // Acepta solo URLs https razonables; si no, devuelve null.
    static String validar(String valor) {
        String url = valor.startsWith("//") ? "https:" + valor : valor;
        if (url.length() > 2048) {
            return null;
        }
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getUserInfo() == null
                    && host != null && CDN.contains(host.toLowerCase(Locale.ROOT)) ? url : null;
        } catch (IllegalArgumentException error) {
            return null;
        }
    }
}
