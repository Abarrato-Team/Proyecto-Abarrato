package com.abarrato.app;

import org.json.JSONObject;

import java.io.IOException;

/**
 * Error que el servidor explicó con su formato estándar
 * ({"estado":400,"codigo":"SOLICITUD_INVALIDA","mensaje":"…"}).
 *
 * <p>Es una {@link IOException} para que el código que ya captura errores de red la siga
 * atrapando, pero trae el mensaje en español que el servidor escribió para el usuario.
 */
final class ErrorApi extends IOException {
    final int estado;
    final String codigo;

    // Se crea con desde(...), que lee el JSON de error del servidor.
    private ErrorApi(int estado, String codigo, String mensaje) {
        super(mensaje);
        this.estado = estado;
        this.codigo = codigo;
    }

    /** Lee el cuerpo de error; si no viene en el formato esperado, usa un texto genérico. */
    static ErrorApi desde(int estado, String cuerpo) {
        String codigo = "HTTP_" + estado;
        String mensaje = estado >= 500
                ? "El servidor de precios tuvo un problema. Intenta de nuevo en unos minutos."
                : "La búsqueda no es válida.";
        try {
            JSONObject json = new JSONObject(cuerpo);
            codigo = json.optString("codigo", codigo);
            String recibido = json.optString("mensaje", "").trim();
            if (!recibido.isEmpty()) {
                mensaje = recibido;
            }
        } catch (Exception sinFormato) {
            // Respuesta sin JSON (por ejemplo, una página de error del proxy): queda el texto genérico.
        }
        return new ErrorApi(estado, codigo, mensaje);
    }

    /** true si el problema está en lo que pidió la app (4xx), no en el servidor. */
    boolean esDelCliente() {
        return estado >= 400 && estado < 500;
    }
}
