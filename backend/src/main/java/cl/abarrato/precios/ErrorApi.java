package cl.abarrato.precios;

import java.time.OffsetDateTime;

/**
 * Formato único de error de la API: todos los errores, de cualquier ruta, responden con este JSON.
 *
 * <pre>{"estado":400,"codigo":"SOLICITUD_INVALIDA","mensaje":"Escribe al menos 2 letras",
 *  "ruta":"/api/v1/precios/en-vivo","fecha":"2026-11-13T10:00:00-03:00"}</pre>
 *
 * @param estado  código HTTP repetido en el cuerpo, útil cuando el cliente solo guarda el JSON
 * @param codigo  identificador estable para programas (no cambia aunque cambie el texto)
 * @param mensaje texto en español pensado para mostrarlo al usuario
 * @param ruta    ruta que falló
 * @param fecha   momento del error, en hora de Chile
 */
public record ErrorApi(int estado, String codigo, String mensaje, String ruta, OffsetDateTime fecha) {}
