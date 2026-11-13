package cl.abarrato.precios;

import java.time.OffsetDateTime;

public record ObservacionPrecio(
        Comercio comercio, String productoId, String ubicacion, String nombre,
        String precioTexto, int precioClp, String urlFuente, OffsetDateTime observadoEn,
        String claveComparacion, String urlImagen) {
    public ObservacionPrecio(Comercio comercio, String productoId, String ubicacion, String nombre,
            String precioTexto, int precioClp, String urlFuente, OffsetDateTime observadoEn) {
        this(comercio, productoId, ubicacion, nombre, precioTexto, precioClp, urlFuente, observadoEn, null, null);
    }

    public ObservacionPrecio(Comercio comercio, String productoId, String ubicacion, String nombre,
            String precioTexto, int precioClp, String urlFuente, OffsetDateTime observadoEn, String claveComparacion) {
        this(comercio, productoId, ubicacion, nombre, precioTexto, precioClp, urlFuente, observadoEn,
                claveComparacion, null);
    }

    public String getClaveCarrito() {
        return IdentidadProducto.clave(this);
    }
}
