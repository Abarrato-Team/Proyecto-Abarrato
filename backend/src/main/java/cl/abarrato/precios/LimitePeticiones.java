package cl.abarrato.precios;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Seguridad · límite de peticiones (rate limiting) y tamaño máximo del cuerpo.
 *
 * <p>Cada búsqueda en vivo hace que el servidor visite las páginas de 8 supermercados. Sin un límite,
 * alguien con un script podría mandar miles de búsquedas por minuto: el servidor se saturaría (y la
 * cuenta de Google Cloud subiría) y los supermercados podrían bloquear a Abarrato.
 *
 * <p>Reglas, por minuto:
 * <ul>
 *   <li>Cada cliente (dirección IP): {@code abarrato.seguridad.por-cliente} peticiones en total y
 *       {@code abarrato.seguridad.costosas-por-cliente} de las que visitan los supermercados.</li>
 *   <li>Todos juntos: {@code abarrato.seguridad.costosas-total} peticiones costosas. Es la red de
 *       seguridad si alguien falsifica su IP para saltarse el límite por cliente.</li>
 * </ul>
 * Al pasarse se responde <b>429 Too Many Requests</b> con {@code Retry-After} (segundos que faltan).
 * Cada respuesta informa el saldo en {@code X-RateLimit-Limit} y {@code X-RateLimit-Remaining}.
 * Además, un cuerpo de más de {@value #MAX_CUERPO} bytes se rechaza con <b>413</b>.
 *
 * <p>Este filtro corre antes que los controladores, por eso escribe él mismo el JSON de error,
 * con el mismo formato que {@link ManejadorErrores}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class LimitePeticiones implements Filter {
    private static final Logger LOG = LoggerFactory.getLogger(LimitePeticiones.class);
    private static final ZoneId CHILE = ZoneId.of("America/Santiago");
    static final long VENTANA_MS = 60_000;
    /** Tamaño máximo del cuerpo de una petición: un carrito de 50 productos ocupa unos 10 KB. */
    static final int MAX_CUERPO = 64 * 1024;
    /** Si hay demasiadas IP en memoria se limpian las ventanas vencidas, para no crecer sin fin. */
    private static final int MAX_CLIENTES_EN_MEMORIA = 20_000;

    /** Contador de un minuto. {@code inicio} marca cuándo empezó la ventana. */
    private static final class Ventana {
        final long inicio;
        int total;
        int costosas;

        // Una ventana empieza en cero al comenzar cada minuto.
        Ventana(long inicio) {
            this.inicio = inicio;
        }
    }

    private final int porCliente;
    private final int costosasPorCliente;
    private final int costosasTotal;
    private final Clock reloj;
    private final Map<String, Ventana> clientes = new ConcurrentHashMap<>();
    private Ventana global;

    // Lee los límites desde application.yml (o variables de entorno).
    @Autowired
    public LimitePeticiones(@Value("${abarrato.seguridad.por-cliente:300}") int porCliente,
            @Value("${abarrato.seguridad.costosas-por-cliente:60}") int costosasPorCliente,
            @Value("${abarrato.seguridad.costosas-total:600}") int costosasTotal) {
        this(porCliente, costosasPorCliente, costosasTotal, Clock.systemUTC());
    }

    LimitePeticiones(int porCliente, int costosasPorCliente, int costosasTotal, Clock reloj) {
        if (porCliente < 1 || costosasPorCliente < 1 || costosasTotal < 1) {
            throw new IllegalStateException("Los límites de peticiones deben ser positivos");
        }
        this.porCliente = porCliente;
        this.costosasPorCliente = costosasPorCliente;
        this.costosasTotal = costosasTotal;
        this.reloj = reloj;
        this.global = new Ventana(reloj.millis());
    }

    // Se ejecuta en cada petición: revisa tamaño y cupo antes de llegar al controlador.
    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain cadena)
            throws IOException, ServletException {
        HttpServletRequest peticion = (HttpServletRequest) req;
        HttpServletResponse respuesta = (HttpServletResponse) res;
        String ruta = peticion.getRequestURI();

        // La tarea diaria tiene su propio token y dura 25 minutos: no se limita.
        if (!ruta.startsWith("/api/") || ruta.equals("/api/v1/precios/actualizar")) {
            cadena.doFilter(req, res);
            return;
        }
        if (peticion.getContentLengthLong() > MAX_CUERPO) {
            escribirError(respuesta, 413, "CUERPO_DEMASIADO_GRANDE",
                    "La petición es demasiado grande (máximo " + (MAX_CUERPO / 1024) + " KB)", ruta);
            return;
        }

        boolean costosa = esCostosa(peticion);
        String cliente = cliente(peticion);
        long ahora = reloj.millis();
        Decision decision = registrar(cliente, costosa, ahora);

        respuesta.setHeader("X-RateLimit-Limit", String.valueOf(decision.limite));
        respuesta.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, decision.restantes)));
        if (!decision.permitida) {
            long segundos = Math.max(1, (decision.reinicioMs - ahora + 999) / 1000);
            respuesta.setHeader("Retry-After", String.valueOf(segundos));
            LOG.warn("Límite de peticiones alcanzado ({}) por {} en {}", decision.motivo, cliente, ruta);
            escribirError(respuesta, 429, "DEMASIADAS_PETICIONES",
                    "Hiciste muchas búsquedas seguidas. Espera " + segundos + " segundos y vuelve a intentar", ruta);
            return;
        }
        cadena.doFilter(req, res);
    }

    // Resultado de revisar el cupo: si pasa, el límite, lo que queda y cuándo se renueva.
    record Decision(boolean permitida, int limite, int restantes, long reinicioMs, String motivo) {}

    /** Cuenta la petición y decide. Sincronizado: dos peticiones simultáneas no pueden colarse en el último cupo. */
    synchronized Decision registrar(String cliente, boolean costosa, long ahora) {
        if (ahora - global.inicio >= VENTANA_MS) {
            global = new Ventana(ahora);
        }
        if (clientes.size() > MAX_CLIENTES_EN_MEMORIA) {
            clientes.values().removeIf(ventana -> ahora - ventana.inicio >= VENTANA_MS);
        }
        Ventana propia = clientes.compute(cliente, (clave, actual) ->
                actual == null || ahora - actual.inicio >= VENTANA_MS ? new Ventana(ahora) : actual);
        long reinicio = propia.inicio + VENTANA_MS;

        if (propia.total >= porCliente) {
            return new Decision(false, porCliente, 0, reinicio, "total por cliente");
        }
        if (costosa && propia.costosas >= costosasPorCliente) {
            return new Decision(false, costosasPorCliente, 0, reinicio, "búsquedas por cliente");
        }
        if (costosa && global.costosas >= costosasTotal) {
            return new Decision(false, costosasPorCliente, 0, global.inicio + VENTANA_MS, "total del servidor");
        }
        propia.total++;
        if (costosa) {
            propia.costosas++;
            global.costosas++;
            return new Decision(true, costosasPorCliente, costosasPorCliente - propia.costosas, reinicio, "");
        }
        return new Decision(true, porCliente, porCliente - propia.total, reinicio, "");
    }

    /** Las rutas que salen a visitar las páginas de los supermercados. */
    static boolean esCostosa(HttpServletRequest peticion) {
        String ruta = peticion.getRequestURI();
        if (ruta.equals("/api/v1/precios/en-vivo")) {
            return !"true".equalsIgnoreCase(peticion.getParameter("guardado"));
        }
        return ruta.equals("/api/v1/precios/comparar-carrito")
                || ruta.startsWith("/api/v1/precios/categorias/")
                || (ruta.startsWith("/api/v1/precios/tiendas/") && ruta.endsWith("/productos"));
    }

    /**
     * IP del cliente. En Google Cloud Run la conexión llega desde el balanceador de Google, y la IP real
     * viene en el encabezado X-Forwarded-For (la primera de la lista).
     */
    static String cliente(HttpServletRequest peticion) {
        String reenviada = peticion.getHeader("X-Forwarded-For");
        if (reenviada != null && !reenviada.isBlank()) {
            String primera = reenviada.split(",")[0].strip();
            if (pareceIp(primera)) {
                return primera;
            }
        }
        String directa = peticion.getRemoteAddr();
        return pareceIp(directa) ? directa : "desconocido";
    }

    /**
     * Solo acepta textos con forma de IPv4 o IPv6 (dígitos, letras a-f, puntos y dos puntos).
     * El encabezado lo escribe el cliente: sin este filtro alguien podría meter saltos de línea y
     * falsificar renglones del log (OWASP A09:2025, CWE-117) o llenar la memoria con claves enormes.
     */
    static boolean pareceIp(String texto) {
        return texto != null && !texto.isEmpty() && texto.length() <= 45
                && texto.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
                        || (c >= 'A' && c <= 'F') || c == '.' || c == ':');
    }

    // Escribe el JSON de error con el mismo formato que ManejadorErrores.
    private static void escribirError(HttpServletResponse respuesta, int estado, String codigo, String mensaje,
            String ruta) throws IOException {
        respuesta.setStatus(estado);
        respuesta.setContentType("application/json");
        respuesta.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String json = "{\"estado\":" + estado
                + ",\"codigo\":\"" + codigo + "\""
                + ",\"mensaje\":\"" + textoJson(mensaje) + "\""
                + ",\"ruta\":\"" + textoJson(ruta) + "\""
                + ",\"fecha\":\"" + OffsetDateTime.now(CHILE).withNano(0) + "\"}";
        respuesta.getWriter().write(json);
    }

    /** Escapa comillas, barras y caracteres de control para que el texto no rompa el JSON. */
    static String textoJson(String texto) {
        StringBuilder limpio = new StringBuilder(texto.length());
        for (char c : texto.toCharArray()) {
            switch (c) {
                case '"' -> limpio.append("\\\"");
                case '\\' -> limpio.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        limpio.append(String.format("\\u%04x", (int) c));
                    } else {
                        limpio.append(c);
                    }
                }
            }
        }
        return limpio.toString();
    }
}
