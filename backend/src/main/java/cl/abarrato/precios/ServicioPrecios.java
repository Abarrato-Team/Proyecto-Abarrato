package cl.abarrato.precios;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Capa de negocio de los precios guardados. Los controladores hablan con esta clase y nunca con
 * {@link RepositorioPrecios}: aquí viven las reglas (límites, qué es una sucursal válida, qué hacer
 * cuando no hay datos) y el repositorio solo sabe leer y escribir en PostgreSQL.
 */
@Service
public class ServicioPrecios {
    /** Máximo de fotos por petición, para que una sola llamada no recorra toda la tabla. */
    static final int MAX_FOTOS = 50;
    /** Largo máximo de los textos de búsqueda e identificadores que llegan del cliente. */
    static final int MAX_TEXTO = 120;

    private final RepositorioPrecios repositorio;

    // Recibe el repositorio (capa de datos) por constructor.
    public ServicioPrecios(RepositorioPrecios repositorio) {
        this.repositorio = repositorio;
    }

    // Guarda una lista de precios en una sola transacción.
    @Transactional
    public void importar(List<ObservacionPrecio> observaciones) {
        repositorio.guardar(observaciones);
    }

    /** Como {@link #importar}, pero sin repetir filas cuando el precio no cambió. Devuelve las filas nuevas. */
    @Transactional
    public int importarSiCambio(List<ObservacionPrecio> observaciones) {
        return repositorio.guardarSiCambio(observaciones);
    }

    /** Último precio de un producto en una tienda; 404 si nunca se ha leído. */
    public ObservacionPrecio buscarUltimo(Comercio comercio, String productoId, String ubicacion) {
        if (productoId == null || productoId.isBlank() || productoId.length() > MAX_TEXTO
                || ubicacion == null || ubicacion.isBlank() || ubicacion.length() > MAX_TEXTO) {
            throw new SolicitudInvalidaException(
                    "productoId y ubicacion son obligatorios y de máximo " + MAX_TEXTO + " caracteres");
        }
        return repositorio.buscarUltimo(comercio, productoId, ubicacion)
                .orElseThrow(() -> new RecursoNoEncontradoException(
                        "No hay precios guardados para ese producto en " + comercio.name()));
    }

    // Precio de cada producto antes de una fecha (para "bajó desde ayer").
    public Map<RepositorioPrecios.ProductoTienda, Integer> preciosAnteriores(
            Set<RepositorioPrecios.ProductoTienda> productos, OffsetDateTime antesDe) {
        return repositorio.buscarPreciosAnteriores(productos, antesDe);
    }

    /**
     * Catálogo guardado. Sin comercio busca en todas las tiendas web; con comercio exige también una
     * sucursal física y busca solo en ella.
     */
    public List<ObservacionPrecio> buscarCatalogo(String texto, Comercio comercio, String ubicacion) {
        String limpio = texto == null ? "" : texto.strip();
        if (limpio.length() > MAX_TEXTO) {
            throw new SolicitudInvalidaException("La búsqueda admite máximo " + MAX_TEXTO + " caracteres");
        }
        if (comercio == null && ubicacion == null) {
            return repositorio.buscarCatalogo(limpio);
        }
        if (comercio == null || !ComparadorCarrito.esSucursal(ubicacion)) {
            throw new SolicitudInvalidaException("Para buscar en una sucursal indica comercio y ubicacion juntos");
        }
        return repositorio.buscarCatalogoSucursal(comercio, ubicacion, limpio);
    }

    // Atajo: catálogo web guardado, sin sucursal.
    public List<ObservacionPrecio> buscarCatalogo(String texto) {
        return buscarCatalogo(texto, null, null);
    }

    // Sucursales físicas con precios guardados.
    public List<RepositorioPrecios.Sucursal> sucursales(Comercio comercio) {
        return repositorio.buscarSucursales(comercio);
    }

    /** Foto más reciente de cada producto pedido (de 1 a {@value #MAX_FOTOS} claves de catálogo). */
    public List<RepositorioPrecios.Foto> fotos(List<String> claves) {
        if (claves == null || claves.isEmpty() || claves.size() > MAX_FOTOS) {
            throw new SolicitudInvalidaException("Pide de 1 a " + MAX_FOTOS + " fotos por consulta");
        }
        if (claves.stream().anyMatch(clave -> !IdentidadProducto.esClaveValida(clave))) {
            throw new SolicitudInvalidaException("Alguna clave de producto no tiene el formato del catálogo");
        }
        return repositorio.buscarFotos(Set.copyOf(claves));
    }
}
