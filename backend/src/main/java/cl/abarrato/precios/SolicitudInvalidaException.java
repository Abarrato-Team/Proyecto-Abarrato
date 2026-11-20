package cl.abarrato.precios;

/**
 * La petición del cliente no cumple el contrato de la API (dato faltante, fuera de rango o mal formado).
 *
 * <p>Extiende {@link IllegalArgumentException} para que el código que ya validaba con esa excepción siga
 * funcionando, pero tiene nombre propio: así el manejador central la distingue de un error de
 * programación y responde 400 con el mensaje pensado para el usuario.
 */
public class SolicitudInvalidaException extends IllegalArgumentException {
    // El mensaje se muestra al cliente en el JSON de error.
    public SolicitudInvalidaException(String mensaje) {
        super(mensaje);
    }
}
