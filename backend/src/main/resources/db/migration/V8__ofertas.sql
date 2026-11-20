-- Ofertas publicadas por cada tienda junto al precio que se compara.
-- precio_antes_clp: precio normal tachado cuando precio_clp ya es una rebaja.
-- precio_oferta_clp y condicion_oferta: precio más bajo que exige algo (tarjeta, cantidad, club).
ALTER TABLE observaciones_precio ADD COLUMN IF NOT EXISTS precio_antes_clp integer;
ALTER TABLE observaciones_precio ADD COLUMN IF NOT EXISTS precio_oferta_clp integer;
ALTER TABLE observaciones_precio ADD COLUMN IF NOT EXISTS condicion_oferta text;
