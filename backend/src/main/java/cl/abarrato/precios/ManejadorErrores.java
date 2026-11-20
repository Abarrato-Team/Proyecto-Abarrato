package cl.abarrato.precios;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Manejador central de errores: traduce cada excepción a un código HTTP y al formato {@link ErrorApi}.
 *
 * <p>Los controladores no arman respuestas de error: solo lanzan la excepción que corresponde.
 * Los errores inesperados se registran completos en el log, pero al cliente nunca se le envía
 * el detalle interno (traza, SQL, nombres de clases).
 */
@RestControllerAdvice
public class ManejadorErrores {
    private static final Logger LOG = LoggerFactory.getLogger(ManejadorErrores.class);
    private static final ZoneId CHILE = ZoneId.of("America/Santiago");

    // 400: un dato no cumple las reglas.
    @ExceptionHandler(SolicitudInvalidaException.class)
    ResponseEntity<ErrorApi> solicitudInvalida(SolicitudInvalidaException error, HttpServletRequest peticion) {
        return responder(HttpStatus.BAD_REQUEST, "SOLICITUD_INVALIDA", error.getMessage(), peticion);
    }

    // 400: falta un parámetro obligatorio.
    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ErrorApi> parametroFaltante(MissingServletRequestParameterException error,
            HttpServletRequest peticion) {
        return responder(HttpStatus.BAD_REQUEST, "PARAMETRO_FALTANTE",
                "Falta el parámetro '" + error.getParameterName() + "'", peticion);
    }

    // 400: falta un encabezado obligatorio.
    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ErrorApi> encabezadoFaltante(MissingRequestHeaderException error, HttpServletRequest peticion) {
        return responder(HttpStatus.BAD_REQUEST, "PARAMETRO_FALTANTE",
                "Falta el encabezado '" + error.getHeaderName() + "'", peticion);
    }

    // 400: un parámetro tiene el tipo equivocado (ej. tienda inexistente).
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorApi> tipoInvalido(MethodArgumentTypeMismatchException error, HttpServletRequest peticion) {
        String mensaje = "Valor no válido para '" + error.getName() + "'";
        Class<?> tipo = error.getRequiredType();
        if (tipo != null && tipo.isEnum()) {
            // Ej.: comercio=FOO → "usa uno de: JUMBO, ALVI, ...". Ayuda a quien prueba la API con curl.
            mensaje += "; usa uno de: " + String.join(", ",
                    java.util.Arrays.stream(tipo.getEnumConstants()).map(String::valueOf).toList());
        }
        return responder(HttpStatus.BAD_REQUEST, "VALOR_INVALIDO", mensaje, peticion);
    }

    // 400: el cuerpo del POST no es JSON válido.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorApi> cuerpoIlegible(HttpMessageNotReadableException error, HttpServletRequest peticion) {
        return responder(HttpStatus.BAD_REQUEST, "JSON_INVALIDO",
                "El cuerpo de la petición no es un JSON válido", peticion);
    }

    // 404: no hay datos para lo pedido.
    @ExceptionHandler(RecursoNoEncontradoException.class)
    ResponseEntity<ErrorApi> noEncontrado(RecursoNoEncontradoException error, HttpServletRequest peticion) {
        return responder(HttpStatus.NOT_FOUND, "NO_ENCONTRADO", error.getMessage(), peticion);
    }

    // 404: la ruta no existe.
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorApi> rutaInexistente(NoResourceFoundException error, HttpServletRequest peticion) {
        return responder(HttpStatus.NOT_FOUND, "NO_ENCONTRADO", "La ruta no existe", peticion);
    }

    // 403: credencial incorrecta.
    @ExceptionHandler(AccesoDenegadoException.class)
    ResponseEntity<ErrorApi> accesoDenegado(AccesoDenegadoException error, HttpServletRequest peticion) {
        return responder(HttpStatus.FORBIDDEN, "ACCESO_DENEGADO", error.getMessage(), peticion);
    }

    // 405: verbo HTTP equivocado.
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorApi> metodoNoPermitido(HttpRequestMethodNotSupportedException error,
            HttpServletRequest peticion) {
        return responder(HttpStatus.METHOD_NOT_ALLOWED, "METODO_NO_PERMITIDO",
                "La ruta no acepta " + error.getMethod(), peticion);
    }

    // 502: la página del supermercado no respondió.
    @ExceptionHandler({IOException.class, UncheckedIOException.class})
    ResponseEntity<ErrorApi> tiendaInaccesible(Exception error, HttpServletRequest peticion) {
        LOG.warn("Página de un comercio inaccesible en {}: {}", peticion.getRequestURI(), error.toString());
        return responder(HttpStatus.BAD_GATEWAY, "TIENDA_INACCESIBLE",
                "La página del supermercado no respondió; intenta de nuevo en unos minutos", peticion);
    }

    // Errores que ya traen su código HTTP.
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ErrorApi> conEstado(ResponseStatusException error, HttpServletRequest peticion) {
        HttpStatusCode estado = error.getStatusCode();
        String mensaje = error.getReason() != null ? error.getReason() : "No se pudo completar la petición";
        return responder(estado, "ERROR_" + estado.value(), mensaje, peticion);
    }

    // 500: cualquier otro error. Se registra completo en el log, pero no se muestra al cliente.
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorApi> inesperado(Exception error, HttpServletRequest peticion) {
        LOG.error("Error inesperado en {} {}", peticion.getMethod(), peticion.getRequestURI(), error);
        return responder(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_INTERNO",
                "Ocurrió un error en el servidor; intenta de nuevo", peticion);
    }

    // Arma la respuesta con el formato único de error.
    private static ResponseEntity<ErrorApi> responder(HttpStatusCode estado, String codigo, String mensaje,
            HttpServletRequest peticion) {
        return ResponseEntity.status(estado).body(new ErrorApi(estado.value(), codigo, mensaje,
                peticion.getRequestURI(), OffsetDateTime.now(CHILE).withNano(0)));
    }
}
