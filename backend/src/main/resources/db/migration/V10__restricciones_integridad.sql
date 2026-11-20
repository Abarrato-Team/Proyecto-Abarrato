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
