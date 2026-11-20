package cl.abarrato.precios;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// Descarga páginas de los supermercados (con tiempo máximo y proxy opcional) y les extrae los precios.
@Component
final class CapturadorPrecios {
    private static final String FICHA_LIDER_SOPROLE =
            "https://super.lider.cl/ip/leche/leche-entera-natural-1-l/00780290000130";

    /** Segundos de espera por página. En Render gratis conviene subirlo con ABARRATO_TIMEOUT_SEGUNDOS=40. */
    private static final int TIEMPO_ESPERA_MS =
            CatalogoEnVivo.entero("ABARRATO_TIMEOUT_SEGUNDOS", 10, 5, 120) * 1000;

    private final ExtractorPrecios extractor;
    private final String proxyHost;
    private final int proxyPuerto;

    CapturadorPrecios(ExtractorPrecios extractor,
            @Value("${abarrato.recoleccion.proxy-host}") String proxyHost,
            @Value("${abarrato.recoleccion.proxy-puerto}") int proxyPuerto) {
        if (proxyPuerto < 0 || proxyPuerto > 65535 || proxyHost.isBlank() != (proxyPuerto == 0)) {
            throw new IllegalArgumentException("Configura host y puerto del proxy juntos");
        }
        this.extractor = extractor;
        this.proxyHost = proxyHost;
        this.proxyPuerto = proxyPuerto;
    }

    // Lee la portada o catálogo principal de una tienda.
    List<ObservacionPrecio> capturar(Comercio comercio) throws IOException {
        Document pagina = descargar(comercio.catalogo());
        List<ObservacionPrecio> precios = extractor.extraer(comercio, pagina, ahora());
        if (comercio != Comercio.LIDER) {
            return precios;
        }
        List<ObservacionPrecio> ofertas = new ArrayList<>(precios);
        try {
            extractor.lecheLider(descargar(FICHA_LIDER_SOPROLE), FICHA_LIDER_SOPROLE, ahora())
                    .ifPresent(oferta -> {
                        ofertas.removeIf(anterior -> anterior.productoId().equals(oferta.productoId()));
                        ofertas.add(oferta);
                    });
        } catch (IOException ignored) {
            // La ficha suplementaria no invalida los precios de la vitrina.
        }
        return List.copyOf(ofertas);
    }

    // Lee una página específica (búsqueda o pasillo) de una tienda.
    List<ObservacionPrecio> capturarPagina(Comercio comercio, String url) throws IOException {
        if (comercio == Comercio.DEL_PEDREGAL && url.startsWith(Comercio.CATALOGO_PEDREGAL)) {
            return catalogoPedregal();
        }
        return extractor.extraer(comercio, descargar(url), ahora());
    }

    /** Recorre las páginas de products.json de Del Pedregal (250 productos por página). */
    private List<ObservacionPrecio> catalogoPedregal() throws IOException {
        List<ObservacionPrecio> precios = new ArrayList<>();
        OffsetDateTime instante = ahora();
        for (int pagina = 1; pagina <= 12; pagina++) {
            List<ObservacionPrecio> lote = extractor.pedregalJson(
                    descargarTexto(Comercio.CATALOGO_PEDREGAL + "&page=" + pagina), instante);
            if (lote.isEmpty()) {
                break;
            }
            precios.addAll(lote);
        }
        if (precios.isEmpty()) {
            throw new IOException("El catálogo de Del Pedregal no contiene precios reconocibles");
        }
        return List.copyOf(precios);
    }

    // Descarga una URL como texto plano (para respuestas JSON).
    String descargarTexto(String url) throws IOException {
        var solicitud = Jsoup.connect(url)
                .userAgent("AbarratoPriceBot/0.2")
                .ignoreContentType(true)
                .followRedirects(false)
                .timeout(TIEMPO_ESPERA_MS)
                .maxBodySize(5 * 1024 * 1024);
        if (!proxyHost.isBlank()) {
            solicitud.proxy(proxyHost, proxyPuerto);
        }
        var respuesta = solicitud.execute();
        if (respuesta.statusCode() != 200) {
            throw new IOException("HTTP " + respuesta.statusCode());
        }
        return respuesta.body();
    }

    // Descarga una URL y la convierte en documento HTML con jsoup.
    Document descargar(String url) throws IOException {
        var solicitud = Jsoup.connect(url)
                .userAgent("AbarratoPriceBot/0.2")
                .followRedirects(false)
                .timeout(TIEMPO_ESPERA_MS)
                .maxBodySize(5 * 1024 * 1024);
        if (!proxyHost.isBlank()) {
            solicitud.proxy(proxyHost, proxyPuerto);
        }
        var respuesta = solicitud.execute();
        if (respuesta.statusCode() != 200) {
            throw new IOException("HTTP " + respuesta.statusCode());
        }
        return respuesta.parse();
    }

    // Hora actual en Chile, para sellar cada precio leído.
    private OffsetDateTime ahora() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
