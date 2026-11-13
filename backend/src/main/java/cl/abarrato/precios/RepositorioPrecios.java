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

@Repository
public class RepositorioPrecios {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate parametros;

    public RepositorioPrecios(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.parametros = new NamedParameterJdbcTemplate(jdbc);
    }

    void guardar(List<ObservacionPrecio> observaciones) {
        jdbc.batchUpdate("""
                INSERT INTO observaciones_precio
                    (comercio, producto_id, ubicacion, nombre, precio_texto, precio_clp, url_fuente, observado_en,
                    clave_comparacion, url_imagen)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
        });
    }

    Optional<ObservacionPrecio> buscarUltimo(Comercio comercio, String productoId, String ubicacion) {
        return jdbc.query("""
                SELECT nombre, precio_texto, precio_clp, url_fuente, observado_en, clave_comparacion, url_imagen
                FROM observaciones_precio
                WHERE comercio = ? AND producto_id = ? AND ubicacion = ?
                ORDER BY observado_en DESC
                LIMIT 1
                """, (rs, row) -> new ObservacionPrecio(comercio, productoId, ubicacion,
                rs.getString("nombre"), rs.getString("precio_texto"), rs.getInt("precio_clp"),
                rs.getString("url_fuente"), rs.getObject("observado_en", OffsetDateTime.class),
                rs.getString("clave_comparacion"), rs.getString("url_imagen")),
                comercio.name(), productoId, ubicacion).stream().findFirst();
    }

    List<ObservacionPrecio> buscarCatalogo(String texto) {
        /*
         * Primero se buscan por nombre los productos candidatos (usa el índice de trigramas de V7) y
         * solo de esos se toma la observación más reciente, en vez de ordenar toda la tabla.
         */
        String patron = "%" + texto.toLowerCase(java.util.Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbc.query("""
                SELECT comercio, producto_id, ubicacion, nombre, precio_texto, precio_clp, url_fuente, observado_en,
                    clave_comparacion, url_imagen
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
                LIMIT 60
                """, this::observacion, patron, patron);
    }

    record Sucursal(Comercio comercio, String ubicacion, OffsetDateTime ultimaLectura) {}

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

    private ObservacionPrecio observacion(ResultSet rs, int row) throws SQLException {
        return new ObservacionPrecio(Comercio.valueOf(rs.getString("comercio")),
                rs.getString("producto_id"), rs.getString("ubicacion"), rs.getString("nombre"),
                rs.getString("precio_texto"), rs.getInt("precio_clp"), rs.getString("url_fuente"),
                rs.getObject("observado_en", OffsetDateTime.class), rs.getString("clave_comparacion"),
                rs.getString("url_imagen"));
    }

    record Foto(String claveCarrito, String urlImagen) {}

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
