package cl.abarrato.precios;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Seguridad · cabeceras HTTP que se agregan a todas las respuestas.
 *
 * <p>La API solo entrega JSON, así que se le dice al navegador (si alguien la abre en uno) que no
 * interprete nada como página web, que no la muestre dentro de otra página y que use siempre HTTPS.
 * No se agregan cabeceras CORS: ninguna página web de otro dominio puede llamar a la API desde el
 * navegador; la app Android no las necesita.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CabecerasSeguridad implements Filter {
    // Agrega las cabeceras a la respuesta y deja seguir la petición.
    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain cadena)
            throws IOException, ServletException {
        HttpServletResponse respuesta = (HttpServletResponse) res;
        // No adivinar el tipo de contenido: JSON es JSON.
        respuesta.setHeader("X-Content-Type-Options", "nosniff");
        // Nadie puede incrustar la API en un <iframe>.
        respuesta.setHeader("X-Frame-Options", "DENY");
        // Si una respuesta se abriera en un navegador, no puede cargar scripts, imágenes ni nada.
        respuesta.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        // No enviar a otros sitios la URL desde donde se llegó.
        respuesta.setHeader("Referrer-Policy", "no-referrer");
        // Durante un año, el navegador solo se conecta por HTTPS (Cloud Run ya sirve HTTPS).
        respuesta.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        cadena.doFilter(req, res);
    }
}
