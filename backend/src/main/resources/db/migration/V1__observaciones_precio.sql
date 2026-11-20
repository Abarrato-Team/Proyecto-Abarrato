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
