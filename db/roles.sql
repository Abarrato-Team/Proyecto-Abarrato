-- =====================================================================================
-- Abarrato · Perfiles de acceso a la base de datos (ISO/IEC 27002:2022, controles 5.15 a 5.18,
-- 8.2 y 8.3: control de acceso, identidades, derechos de acceso y privilegios mínimos).
--
-- Tres cuentas, cada una con lo justo para su trabajo:
--   neondb_owner      dueño del esquema. Solo lo usa Flyway para crear y cambiar tablas.
--   abarrato_app      la que usa el servidor en Cloud Run: leer, insertar y actualizar precios.
--                     NO puede borrar filas, borrar tablas ni cambiar el esquema.
--   abarrato_lectura  para consultas manuales, reportes o el profesor: solo puede leer.
--
-- CÓMO APLICARLO (una sola vez):
--   1. En la consola de Neon: Branches → main → Roles → "New role". Crea "abarrato_app" y
--      "abarrato_lectura". Neon genera la contraseña: cópiala directo a Cloud Run, no la guardes
--      en ningún archivo ni la pegues en un chat.
--   2. En Neon → SQL Editor, conectado como neondb_owner, ejecuta este archivo completo.
--   3. En Cloud Run, cambia las variables:
--        ABARRATO_DB_USER=abarrato_app        ABARRATO_DB_PASSWORD=<clave de abarrato_app>
--        ABARRATO_MIGRATION_USER=neondb_owner ABARRATO_MIGRATION_PASSWORD=<clave de neondb_owner>
--   El script se puede ejecutar varias veces sin romper nada.
-- =====================================================================================

-- Nadie, salvo los roles listados abajo, puede entrar al esquema ni crear objetos en él.
REVOKE ALL ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC;

-- ---------- abarrato_app: el servidor ----------
GRANT CONNECT ON DATABASE neondb TO abarrato_app;
GRANT USAGE ON SCHEMA public TO abarrato_app;
-- El repositorio solo hace SELECT, INSERT y UPDATE (confirmado_en). Sin DELETE ni TRUNCATE.
GRANT SELECT, INSERT, UPDATE ON observaciones_precio TO abarrato_app;
-- Flyway la consulta al arrancar para saber si hay migraciones pendientes.
GRANT SELECT ON flyway_schema_history TO abarrato_app;
-- Límites para que un error o un abuso no acapare la base.
ALTER ROLE abarrato_app CONNECTION LIMIT 10;
ALTER ROLE abarrato_app SET statement_timeout = '30s';
ALTER ROLE abarrato_app SET idle_in_transaction_session_timeout = '60s';

-- ---------- abarrato_lectura: consultas manuales ----------
GRANT CONNECT ON DATABASE neondb TO abarrato_lectura;
GRANT USAGE ON SCHEMA public TO abarrato_lectura;
GRANT SELECT ON observaciones_precio TO abarrato_lectura;
ALTER ROLE abarrato_lectura SET default_transaction_read_only = on;
ALTER ROLE abarrato_lectura CONNECTION LIMIT 3;
ALTER ROLE abarrato_lectura SET statement_timeout = '15s';

-- Tablas que Flyway cree en el futuro heredan los mismos permisos.
ALTER DEFAULT PRIVILEGES FOR ROLE neondb_owner IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE ON TABLES TO abarrato_app;
ALTER DEFAULT PRIVILEGES FOR ROLE neondb_owner IN SCHEMA public
    GRANT SELECT ON TABLES TO abarrato_lectura;

-- ---------- Verificación: debe listar exactamente estos permisos ----------
SELECT grantee, table_name, string_agg(privilege_type, ', ' ORDER BY privilege_type) AS permisos
FROM information_schema.role_table_grants
WHERE grantee IN ('abarrato_app', 'abarrato_lectura')
GROUP BY grantee, table_name
ORDER BY grantee, table_name;
