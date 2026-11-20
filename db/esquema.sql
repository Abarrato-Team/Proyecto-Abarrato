-- =====================================================================================
-- Abarrato · Script que crea la base de datos desde cero (PostgreSQL 15 o superior).
-- Generado juntando, en orden, las migraciones de backend/src/main/resources/db/migration.
-- En el día a día NO hace falta: Flyway aplica las migraciones solo al arrancar el servidor.
-- Uso: psql "$URL_DE_LA_BASE" -f db/esquema.sql   y luego   db/roles.sql
-- =====================================================================================

-- ---------- V1__observaciones_precio.sql ----------
CREATE TABLE observaciones_precio (
    comercio text NOT NULL,
    producto_id text NOT NULL,
    ubicacion text NOT NULL,
    nombre text NOT NULL,
    precio_texto text NOT NULL,
    precio_clp integer NOT NULL CHECK (precio_clp > 0),
    url_fuente text NOT NULL,
    observado_en timestamptz NOT NULL,
    PRIMARY KEY (comercio, producto_id, ubicacion, observado_en)
);

-- ---------- V2__identidad_producto.sql ----------
ALTER TABLE observaciones_precio ADD COLUMN clave_comparacion text;

-- ---------- V3__consultas_por_sucursal.sql ----------
ALTER TABLE observaciones_precio ADD COLUMN url_imagen text;

CREATE INDEX observaciones_precio_por_ubicacion
    ON observaciones_precio (comercio, ubicacion, producto_id, observado_en DESC);

-- ---------- V7__indices_busqueda.sql ----------
-- Índices para que el respaldo de precios (cuando una tienda no responde) sea rápido.

-- Búsqueda por nombre con LIKE '%texto%': índice de trigramas, si la base permite la extensión.
DO $$
BEGIN
    CREATE EXTENSION IF NOT EXISTS pg_trgm;
EXCEPTION WHEN others THEN
    RAISE NOTICE 'pg_trgm no disponible: se omite el índice por nombre';
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm') THEN
        EXECUTE 'CREATE INDEX IF NOT EXISTS observaciones_precio_nombre_trgm
                 ON observaciones_precio USING gin (lower(nombre) gin_trgm_ops)';
    END IF;
END $$;

-- Última observación de cada producto por ubicación (DISTINCT ON ... ORDER BY observado_en DESC).
CREATE INDEX IF NOT EXISTS observaciones_precio_ultima_por_ubicacion
    ON observaciones_precio (ubicacion, comercio, producto_id, observado_en DESC);

-- ---------- V8__ofertas.sql ----------
-- Ofertas publicadas por cada tienda junto al precio que se compara.
-- precio_antes_clp: precio normal tachado cuando precio_clp ya es una rebaja.
-- precio_oferta_clp y condicion_oferta: precio más bajo que exige algo (tarjeta, cantidad, club).
ALTER TABLE observaciones_precio ADD COLUMN IF NOT EXISTS precio_antes_clp integer;
ALTER TABLE observaciones_precio ADD COLUMN IF NOT EXISTS precio_oferta_clp integer;
ALTER TABLE observaciones_precio ADD COLUMN IF NOT EXISTS condicion_oferta text;

-- ---------- V9__confirmado_en.sql ----------
-- La tarea diaria no repite filas si el precio no cambió: solo marca cuándo se volvió a confirmar.
-- Así la base de datos (Neon, plan gratuito) crece solo con precios nuevos o distintos.
ALTER TABLE observaciones_precio ADD COLUMN IF NOT EXISTS confirmado_en timestamptz;

-- ---------- V10__restricciones_integridad.sql ----------
-- Integridad de datos en la propia base (buena práctica: la base no confía ciegamente en la aplicación).
-- Son las mismas reglas que ya aplica ObservacionPrecio en Java; si algún día otro programa o una
-- consulta manual intenta guardar algo incoherente, PostgreSQL lo rechaza.
-- NOT VALID: se aplican a toda fila nueva o modificada, sin bloquear la tabla revisando las antiguas.

ALTER TABLE observaciones_precio
    ADD CONSTRAINT observaciones_precio_antes_mayor
    CHECK (precio_antes_clp IS NULL OR precio_antes_clp > precio_clp) NOT VALID;

ALTER TABLE observaciones_precio
    ADD CONSTRAINT observaciones_precio_oferta_menor
    CHECK (precio_oferta_clp IS NULL OR (precio_oferta_clp > 0 AND precio_oferta_clp < precio_clp)) NOT VALID;

ALTER TABLE observaciones_precio
    ADD CONSTRAINT observaciones_precio_oferta_con_condicion
    CHECK ((precio_oferta_clp IS NULL) = (condicion_oferta IS NULL)
           AND (condicion_oferta IS NULL OR char_length(condicion_oferta) <= 80)) NOT VALID;

COMMENT ON TABLE observaciones_precio IS
    'Cada lectura de un precio publicado por un supermercado. Datos públicos: sin información personal.';
COMMENT ON COLUMN observaciones_precio.precio_clp IS 'Precio que se compara, en pesos chilenos enteros';
COMMENT ON COLUMN observaciones_precio.confirmado_en IS 'Última vez que la tarea diaria vio el mismo precio';
