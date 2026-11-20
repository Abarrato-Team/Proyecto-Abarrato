ALTER TABLE observaciones_precio ADD COLUMN url_imagen text;

CREATE INDEX observaciones_precio_por_ubicacion
    ON observaciones_precio (comercio, ubicacion, producto_id, observado_en DESC);
