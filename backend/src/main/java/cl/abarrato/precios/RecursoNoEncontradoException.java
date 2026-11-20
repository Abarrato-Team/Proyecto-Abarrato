package cl.abarrato.precios;

/** Lo pedido no existe (o no hay datos guardados para ello). El manejador central responde 404. */
public class RecursoNoEncontradoException extends RuntimeException {
    // El mensaje se muestra al cliente en el JSON de error.
    public RecursoNoEncontradoException(String mensaje) {
        super(mensaje);
    }
}
