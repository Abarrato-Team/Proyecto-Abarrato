package cl.abarrato.precios;

import java.time.OffsetDateTime;

/**
 * Un precio leído en una tienda en un momento.
 *
 * {@code precioClp} es el precio que se usa para comparar. Además se guardan, cuando la tienda los
 * publica: {@code precioAntesClp}, el precio normal tachado cuando {@code precioClp} ya es una rebaja,
 * y {@code precioOfertaClp} con su {@code condicionOferta}, un precio más bajo que exige algo extra
 * (pagar con una tarjeta, llevar varias unidades, ser socio del club).
 */
public record ObservacionPrecio(
        Comercio comercio, String productoId, String ubicacion, String nombre,
        String precioTexto, int precioClp, String urlFuente, OffsetDateTime observadoEn,
        String claveComparacion, String urlImagen,
        Integer precioAntesClp, Integer precioOfertaClp, String condicionOferta) {

    public ObservacionPrecio {
        if (precioAntesClp != null && precioAntesClp <= precioClp) {
            precioAntesClp = null;
        }
        if (precioOfertaClp != null && (precioOfertaClp <= 0 || precioOfertaClp >= precioClp)) {
            precioOfertaClp = null;
        }
        if (precioOfertaClp == null) {
            condicionOferta = null;
        } else if (condicionOferta == null || condicionOferta.isBlank()) {
            condicionOferta = "Precio oferta";
        } else if (condicionOferta.length() > 80) {
            condicionOferta = condicionOferta.substring(0, 80);
        }
    }

    public ObservacionPrecio(Comercio comercio, String productoId, String ubicacion, String nombre,
            String precioTexto, int precioClp, String urlFuente, OffsetDateTime observadoEn,
            String claveComparacion, String urlImagen) {
        this(comercio, productoId, ubicacion, nombre, precioTexto, precioClp, urlFuente, observadoEn,
                claveComparacion, urlImagen, null, null, null);
    }

    public ObservacionPrecio(Comercio comercio, String productoId, String ubicacion, String nombre,
            String precioTexto, int precioClp, String urlFuente, OffsetDateTime observadoEn) {
        this(comercio, productoId, ubicacion, nombre, precioTexto, precioClp, urlFuente, observadoEn, null, null);
    }

    public ObservacionPrecio(Comercio comercio, String productoId, String ubicacion, String nombre,
            String precioTexto, int precioClp, String urlFuente, OffsetDateTime observadoEn, String claveComparacion) {
        this(comercio, productoId, ubicacion, nombre, precioTexto, precioClp, urlFuente, observadoEn,
                claveComparacion, null);
    }

    /** Copia con los datos de oferta que publica la tienda. */
    ObservacionPrecio conOferta(Integer antes, Integer oferta, String condicion) {
        return new ObservacionPrecio(comercio, productoId, ubicacion, nombre, precioTexto, precioClp, urlFuente,
                observadoEn, claveComparacion, urlImagen, antes, oferta, condicion);
    }

    /** Si la tienda publica una rebaja o un precio especial para este producto. */
    public boolean isEnOferta() {
        return precioAntesClp != null || precioOfertaClp != null;
    }

    public String getClaveCarrito() {
        return IdentidadProducto.clave(this);
    }
}
