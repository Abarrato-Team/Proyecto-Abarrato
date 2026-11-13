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
