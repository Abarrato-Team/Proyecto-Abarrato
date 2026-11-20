package cl.abarrato.precios;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

// Capa de datos: única clase que habla con PostgreSQL. Todas las consultas usan parámetros (?)
// para evitar inyección SQL.
@Repository
public class RepositorioPrecios {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate parametros;

    // Spring entrega el JdbcTemplate conectado a Neon.
    public RepositorioPrecios(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.parametros = new NamedParameterJdbcTemplate(jdbc);
    }

    // Inserta precios; si la fila ya existe (misma tienda, producto, lugar y hora), la ignora.
    void guardar(List<ObservacionPrecio> observaciones) {
        jdbc.batchUpdate("""
                INSERT INTO observaciones_precio
                    (comercio, producto_id, ubicacion, nombre, precio_texto, precio_clp, url_fuente, observado_en,
                    clave_comparacion, url_imagen, precio_antes_clp, precio_oferta_clp, condicion_oferta)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (comercio, producto_id, ubicacion, observado_en) DO NOTHING
                """, observaciones, 200, (stmt, precio) -> {
            stmt.setString(1, precio.comercio().name());
            stmt.setString(2, precio.productoId());
            stmt.setString(3, precio.ubicacion());
            stmt.setString(4, precio.nombre());
            stmt.setString(5, precio.precioTexto());
            stmt.setInt(6, precio.precioClp());
            stmt.setString(7, precio.urlFuente());
            stmt.setObject(8, precio.observadoEn());
            stmt.setString(9, precio.claveComparacion());
            stmt.setString(10, precio.urlImagen());
            stmt.setObject(11, precio.precioAntesClp(), java.sql.Types.INTEGER);
            stmt.setObject(12, precio.precioOfertaClp(), java.sql.Types.INTEGER);
            stmt.setString(13, precio.condicionOferta());
        });
    }

    /**
     * Guarda solo los precios que cambiaron respecto a la última lectura de cada producto. Si el
     * precio (y su oferta) sigue igual, actualiza {@code confirmado_en} de esa fila en vez de agregar
     * otra. Devuelve cuántas filas nuevas se insertaron.
     */
    int guardarSiCambio(List<ObservacionPrecio> observaciones) {
        if (observaciones.isEmpty()) {
            return 0;
        }
        int[][] confirmadas = jdbc.batchUpdate("""
                UPDATE observaciones_precio SET confirmado_en = ?
                WHERE comercio = ? AND producto_id = ? AND ubicacion = ?
                    AND observado_en = (
                        SELECT max(observado_en) FROM observaciones_precio
                        WHERE comercio = ? AND producto_id = ? AND ubicacion = ?)
                    AND precio_clp = ?
                    AND precio_antes_clp IS NOT DISTINCT FROM ?::integer
                    AND precio_oferta_clp IS NOT DISTINCT FROM ?::integer
                    AND condicion_oferta IS NOT DISTINCT FROM ?::text
                """, observaciones, 200, (stmt, precio) -> {
            stmt.setObject(1, precio.observadoEn());
            stmt.setString(2, precio.comercio().name());
            stmt.setString(3, precio.productoId());
            stmt.setString(4, precio.ubicacion());
            stmt.setString(5, precio.comercio().name());
            stmt.setString(6, precio.productoId());
            stmt.setString(7, precio.ubicacion());
            stmt.setInt(8, precio.precioClp());
            stmt.setObject(9, precio.precioAntesClp(), java.sql.Types.INTEGER);
            stmt.setObject(10, precio.precioOfertaClp(), java.sql.Types.INTEGER);
            stmt.setString(11, precio.condicionOferta());
        });
        List<ObservacionPrecio> nuevas = new java.util.ArrayList<>();
        int indice = 0;
        for (int[] lote : confirmadas) {
            for (int filas : lote) {
                if (filas == 0) {
                    nuevas.add(observaciones.get(indice));
                }
                indice++;
            }
        }
        guardar(nuevas);
        return nuevas.size();
    }

    // Último precio guardado de un producto en una tienda y ubicación.
    Optional<ObservacionPrecio> buscarUltimo(Comercio comercio, String productoId, String ubicacion) {
        return jdbc.query("""
                SELECT *
                FROM observaciones_precio
                WHERE comercio = ? AND producto_id = ? AND ubicacion = ?
                ORDER BY observado_en DESC
                LIMIT 1
                """, this::observacion, comercio.name(), productoId, ubicacion).stream().findFirst();
    }

    // Busca por nombre en el catálogo web guardado (usa el índice de trigramas).
    List<ObservacionPrecio> buscarCatalogo(String texto) {
        /*
         * Primero se buscan por nombre los productos candidatos (usa el índice de trigramas de V7) y
         * solo de esos se toma la observación más reciente, en vez de ordenar toda la tabla.
         */
        String patron = "%" + texto.toLowerCase(java.util.Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbc.query("""
                SELECT *
                FROM (
                    SELECT DISTINCT ON (o.comercio, o.producto_id) o.*
                    FROM observaciones_precio o
                    JOIN (
                        SELECT DISTINCT comercio, producto_id
                        FROM observaciones_precio
                        WHERE ubicacion = 'ONLINE_SIN_UBICACION' AND lower(nombre) LIKE ? ESCAPE '\\'
                    ) candidatos USING (comercio, producto_id)
                    WHERE o.ubicacion = 'ONLINE_SIN_UBICACION'
                    ORDER BY o.comercio, o.producto_id, o.observado_en DESC
                ) vigentes
                WHERE lower(nombre) LIKE ? ESCAPE '\\'
                ORDER BY lower(nombre), comercio, producto_id
                LIMIT 400
                """, this::observacion, patron, patron);
    }

    // Sucursal física con la hora de su última lectura.
    record Sucursal(Comercio comercio, String ubicacion, OffsetDateTime ultimaLectura) {}

    // Sucursales físicas que tienen precios guardados.
    List<Sucursal> buscarSucursales(Comercio comercio) {
        List<String> comercios = comercio == null
                ? java.util.Arrays.stream(Comercio.values()).map(Comercio::name).toList()
                : List.of(comercio.name());
        return parametros.query("""
                SELECT comercio, ubicacion, MAX(observado_en) AS ultima_lectura
                FROM observaciones_precio
                WHERE comercio IN (:comercios) AND ubicacion <> 'ONLINE_SIN_UBICACION'
                    AND observado_en <= CURRENT_TIMESTAMP
                GROUP BY comercio, ubicacion
                ORDER BY comercio, ubicacion
                """, Map.of("comercios", comercios),
                (rs, row) -> new Sucursal(Comercio.valueOf(rs.getString("comercio")),
                        rs.getString("ubicacion"), rs.getObject("ultima_lectura", OffsetDateTime.class)));
    }

    // Busca por nombre en el catálogo de una sucursal física.
    List<ObservacionPrecio> buscarCatalogoSucursal(Comercio comercio, String ubicacion, String texto) {
        return jdbc.query("""
                SELECT *
                FROM (
                    SELECT DISTINCT ON (producto_id) *
                    FROM observaciones_precio
                    WHERE comercio = ? AND ubicacion = ? AND observado_en <= CURRENT_TIMESTAMP
                    ORDER BY producto_id, observado_en DESC
                ) recientes
                WHERE strpos(lower(nombre), lower(?)) > 0
                ORDER BY lower(nombre), producto_id
                LIMIT 60
                """, this::observacion, comercio.name(), ubicacion, texto);
    }

    // Precios más recientes de los productos del carrito en una tienda.
    List<ObservacionPrecio> buscarComparables(
            Comercio comercio, String ubicacion, Set<String> claves, OffsetDateTime hasta) {
        return parametros.query("""
                SELECT *
                FROM (
                    SELECT DISTINCT ON (producto_id) *
                    FROM observaciones_precio
                    WHERE comercio = :comercio AND ubicacion = :ubicacion AND observado_en <= :hasta
                    ORDER BY producto_id, observado_en DESC
                ) recientes
                WHERE COALESCE(clave_comparacion, 'sku:' || comercio || ':' || producto_id) IN (:claves)
                ORDER BY observado_en DESC, precio_clp, producto_id
                """, Map.of("comercio", comercio.name(), "ubicacion", ubicacion, "claves", claves, "hasta", hasta),
                this::observacion);
    }

    // Convierte una fila de la tabla en un objeto ObservacionPrecio.
    private ObservacionPrecio observacion(ResultSet rs, int row) throws SQLException {
        return new ObservacionPrecio(Comercio.valueOf(rs.getString("comercio")),
                rs.getString("producto_id"), rs.getString("ubicacion"), rs.getString("nombre"),
                rs.getString("precio_texto"), rs.getInt("precio_clp"), rs.getString("url_fuente"),
                ultimaLectura(rs), rs.getString("clave_comparacion"),
                rs.getString("url_imagen"), entero(rs, "precio_antes_clp"), entero(rs, "precio_oferta_clp"),
                rs.getString("condicion_oferta"));
    }

    /** Cuándo se vio este precio por última vez: al guardarlo o al confirmarlo sin cambios. */
    private static OffsetDateTime ultimaLectura(ResultSet rs) throws SQLException {
        OffsetDateTime observado = rs.getObject("observado_en", OffsetDateTime.class);
        OffsetDateTime confirmado = rs.getObject("confirmado_en", OffsetDateTime.class);
        return confirmado != null && confirmado.isAfter(observado) ? confirmado : observado;
    }

    // Lee una columna numérica que puede venir vacía (NULL).
    private static Integer entero(ResultSet rs, String columna) throws SQLException {
        int valor = rs.getInt(columna);
        return rs.wasNull() ? null : valor;
    }

    // Par tienda + producto, usado como clave para buscar precios de ayer.
    record ProductoTienda(Comercio comercio, String productoId) {}

    /**
     * Último precio web de cada producto leído antes de {@code antesDe} (normalmente, el inicio del
     * día de hoy en Santiago). Sirve para saber si un precio bajó o subió respecto al día anterior.
     */
    Map<ProductoTienda, Integer> buscarPreciosAnteriores(Set<ProductoTienda> productos, OffsetDateTime antesDe) {
        if (productos.isEmpty()) {
            return Map.of();
        }
        // Una sola consulta para todas las tiendas: se piden las combinaciones posibles y se descartan
        // las que no se buscaron. Ahorra una ida y vuelta a la base de datos por tienda.
        Map<ProductoTienda, Integer> anteriores = new java.util.HashMap<>();
        var comercios = productos.stream().map(p -> p.comercio().name()).distinct().toList();
        var ids = productos.stream().map(ProductoTienda::productoId).distinct().toList();
        parametros.query("""
                SELECT DISTINCT ON (comercio, producto_id) comercio, producto_id, precio_clp
                FROM observaciones_precio
                WHERE comercio IN (:comercios) AND ubicacion = 'ONLINE_SIN_UBICACION'
                    AND producto_id IN (:ids) AND observado_en < :antes
                ORDER BY comercio, producto_id, observado_en DESC
                """, Map.of("comercios", comercios, "ids", ids, "antes", antesDe),
                rs -> {
                    var clave = new ProductoTienda(Comercio.valueOf(rs.getString("comercio")),
                            rs.getString("producto_id"));
                    if (productos.contains(clave)) {
                        anteriores.put(clave, rs.getInt("precio_clp"));
                    }
                });
        return anteriores;
    }

    // Foto más reciente de un producto.
    record Foto(String claveCarrito, String urlImagen) {}

    // Foto más reciente de cada producto pedido.
    List<Foto> buscarFotos(Set<String> claves) {
        return parametros.query("""
                SELECT DISTINCT ON (clave) clave, url_imagen
                FROM (
                    SELECT COALESCE(clave_comparacion, 'sku:' || comercio || ':' || producto_id) AS clave,
                        url_imagen, observado_en
                    FROM observaciones_precio
                    WHERE url_imagen IS NOT NULL
                ) fotos
                WHERE clave IN (:claves)
                ORDER BY clave, observado_en DESC, url_imagen
                """, Map.of("claves", claves), (rs, row) -> new Foto(rs.getString("clave"), rs.getString("url_imagen")));
    }
}
