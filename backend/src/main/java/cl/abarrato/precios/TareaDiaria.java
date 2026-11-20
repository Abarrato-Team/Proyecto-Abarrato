package cl.abarrato.precios;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Actualización diaria de precios, pensada para Cloud Scheduler.
 *
 * En Cloud Run el servidor se apaga cuando no hay visitas, así que la tarea programada interna
 * ({@link BotPrecios}) no corre bien. En su lugar, Cloud Scheduler llama una vez al día a
 * {@code POST /api/v1/precios/actualizar} con el encabezado {@code X-Abarrato-Token}. La petición
 * dura lo que tarda recorrer todas las tiendas, y así quedan guardados los precios de cada día para
 * comparar con el anterior.
 */
@RestController
@RequestMapping("/api/v1/precios")
public class TareaDiaria {
    private static final Logger LOG = LoggerFactory.getLogger(TareaDiaria.class);
    private final CatalogoEnVivo catalogo;
    /** Largo mínimo del token: uno más corto se considera "no configurado". */
    static final int MIN_TOKEN = 16;
    private final String token;
    private final int minutos;

    // Lee el token y la duración del recorrido desde la configuración.
    public TareaDiaria(CatalogoEnVivo catalogo, @Value("${abarrato.tarea.token:}") String token,
            @Value("${abarrato.tarea.minutos:25}") int minutos) {
        this.catalogo = catalogo;
        this.token = token == null ? "" : token.strip();
        // Cloud Scheduler espera como máximo 30 minutos; por defecto se recorre durante 25.
        this.minutos = Math.max(1, Math.min(minutos, 55));
    }

    // POST /actualizar: valida el token y recorre las 8 tiendas guardando los precios.
    @PostMapping("/actualizar")
    public ResponseEntity<Map<String, Object>> actualizar(
            @RequestHeader(value = "X-Abarrato-Token", required = false) String recibido) {
        // Sin token configurado la ruta no existe; con un token distinto se rechaza.
        if (token.length() < MIN_TOKEN) {
            throw new RecursoNoEncontradoException("La ruta no existe");
        }
        // Comparación en tiempo constante: no revela cuántos caracteres del token acertó quien llama.
        if (recibido == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                recibido.strip().getBytes(StandardCharsets.UTF_8))) {
            throw new AccesoDenegadoException("Token de la tarea diaria ausente o incorrecto");
        }
        Instant inicio = Instant.now();
        Map<Comercio, CatalogoEnVivo.Avance> resumen = catalogo.actualizarTodo(minutos);
        long segundos = Duration.between(inicio, Instant.now()).toSeconds();
        long productos = resumen.values().stream().mapToLong(CatalogoEnVivo.Avance::productos).sum();
        long nuevos = resumen.values().stream().mapToLong(CatalogoEnVivo.Avance::nuevos).sum();
        LOG.info("Actualización diaria lista en {} s: {} precios leídos, {} nuevos o distintos. {}",
                segundos, productos, nuevos, resumen);
        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("segundos", segundos);
        respuesta.put("preciosLeidos", productos);
        respuesta.put("preciosNuevos", nuevos);
        respuesta.put("tiendas", resumen);
        return ResponseEntity.ok(respuesta);
    }
}
