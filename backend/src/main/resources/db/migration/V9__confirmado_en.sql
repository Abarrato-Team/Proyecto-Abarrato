-- La tarea diaria no repite filas si el precio no cambió: solo marca cuándo se volvió a confirmar.
-- Así la base de datos (Neon, plan gratuito) crece solo con precios nuevos o distintos.
ALTER TABLE observaciones_precio ADD COLUMN IF NOT EXISTS confirmado_en timestamptz;
