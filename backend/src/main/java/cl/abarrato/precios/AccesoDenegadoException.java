package cl.abarrato.precios;

/** Quien llama no presentó la credencial correcta. El manejador central responde 403. */
public class AccesoDenegadoException extends RuntimeException {
    // El mensaje se muestra al cliente en el JSON de error.
    public AccesoDenegadoException(String mensaje) {
        super(mensaje);
    }
}
