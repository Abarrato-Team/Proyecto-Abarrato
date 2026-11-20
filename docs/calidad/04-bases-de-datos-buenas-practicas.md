# Buenas prácticas de bases de datos (para cualquier motor) · complemento de ISO 27002

> **Para usar con IA:** adjunta este archivo y pide: *"Revisa este esquema/consulta SQL con la lista de verificación de la sección 9 y propón la migración Flyway que corrige cada problema."*

Resumen propio, aplicable a PostgreSQL, MySQL, SQL Server, Oracle u otros motores. Se basa en:

- *Designing Data-Intensive Applications*, 2.ª edición, de Martin Kleppmann y Chris Riccomini (O'Reilly, marzo de 2026): fiabilidad, consistencia, replicación y evolución de esquemas.
- *SQL Antipatterns, Volume 1*, de Bill Karwin (Pragmatic Bookshelf, 2022): errores típicos de diseño lógico, diseño físico, consultas y código de aplicación.
- La documentación oficial de PostgreSQL sobre roles, privilegios y restricciones.

Cada práctica indica el control de **ISO/IEC 27002:2022** que refuerza ("potencia").

---

## 1. Modelado

1. **Una tabla, un concepto.** No guardar listas separadas por comas en una columna; Karwin llama a ese error *Jaywalking*. Si hay varios valores, va una tabla aparte.
2. **Claves primarias con significado claro.** Natural cuando existe y es estable; sustituta (`bigint` o `uuid`) cuando no. Abarrato usa una natural compuesta: `(comercio, producto_id, ubicacion, observado_en)`.
3. **Claves foráneas siempre que exista una relación.** Que la base impida huérfanos, no solo el código.
4. **Normalizar hasta 3FN** y desnormalizar solo con una razón medida, como el rendimiento, dejándola escrita.
5. **Nada de "tablas clonadas"** como `precios_2025` y `precios_2026`. Para volumen se usa particionamiento del motor.
6. **Evitar el EAV** (entidad-atributo-valor, una tabla genérica de "atributos"): se pierden los tipos y las restricciones.

## 2. Tipos de datos

1. **Dinero en enteros o `DECIMAL`/`NUMERIC`, nunca en `FLOAT`/`DOUBLE`.** Los flotantes producen errores de redondeo. Abarrato guarda pesos chilenos como `integer`.
2. **Fechas con zona horaria** (`timestamptz`). Se guarda en UTC y se muestra en la hora local (America/Santiago).
3. **Texto con largo razonable** (`CHECK (char_length(x) <= n)`) cuando el dominio lo permite.
4. **No usar `ENUM` del motor para listas que cambian.** Mejor una tabla de referencia o validar en el código. Abarrato agrega tiendas a menudo, por eso `comercio` es `text`.

## 3. Integridad: la base es la última línea de defensa  ·  ISO 27002 8.28

1. **`NOT NULL` por defecto.** Que `NULL` sea una decisión, no un descuido.
2. **`CHECK` para las reglas simples del dominio**, como precio > 0 u oferta menor que el precio. En Abarrato está en `V10__restricciones_integridad.sql`.
3. **`UNIQUE`** para lo que no debe repetirse.
4. Agregar restricciones a tablas grandes con **`NOT VALID`** (PostgreSQL) y validarlas después, para no bloquear la tabla.
5. **Transacciones** para todo lo que deba ocurrir completo o no ocurrir.

## 4. Consultas  ·  ISO 27002 8.28

1. **Siempre parametrizadas** (`?`, `:nombre`). Nunca concatenar texto del usuario: eso abre la puerta a la inyección SQL (OWASP A05:2025).
2. **Escapar los comodines de `LIKE`** (`%`, `_`) cuando el texto viene del usuario.
3. **No usar `SELECT *` en código nuevo**: hay que listar las columnas. Si alguien agrega una columna sensible, no se filtra sola.
4. **Paginar o limitar** (`LIMIT`) toda consulta que pueda crecer.
5. **Revisar el plan** (`EXPLAIN ANALYZE`) de las consultas frecuentes.
6. Cuidado con `NULL`: `x = NULL` nunca es verdadero; se usa `IS NULL`.

## 5. Índices y rendimiento  ·  ISO 27002 8.6 (gestión de capacidad)

1. Indexar según **cómo se consulta**: columnas del `WHERE`, `JOIN` y `ORDER BY`, en ese orden.
2. Para búsqueda por texto parcial, usar índices especiales (en PostgreSQL, `pg_trgm` con GIN). Abarrato lo tiene en V7.
3. **No indexar todo**: cada índice hace más lentas las escrituras.
4. Medir antes y después; el índice que no se usa se borra.

## 6. Cambios de esquema  ·  ISO 27002 8.32 (gestión de cambios)

1. **Migraciones versionadas** (Flyway, Liquibase). Nunca se cambia la base a mano en producción.
2. **Una migración aplicada no se edita jamás**: se crea una nueva.
3. Cambios **compatibles hacia atrás**: primero agregar la columna y desplegar el código que la usa; recién después borrar lo viejo. Kleppmann lo trata como *evolución de esquemas*.
4. Tener un **script para crear la base desde cero** (`db/esquema.sql`) y probarlo.

## 7. Seguridad y perfiles de acceso  ·  ISO 27002 5.15–5.18, 8.2, 8.3, 8.5

1. **Una cuenta por función**: dueño del esquema (migraciones), aplicación y solo lectura. Nunca la aplicación con la cuenta dueña.
2. **Mínimo privilegio**: la aplicación sin `DROP`, `ALTER`, `TRUNCATE` y, si no lo necesita, sin `DELETE`.
3. **Quitar los permisos por defecto** (`REVOKE ALL ... FROM PUBLIC`).
4. **Límites por cuenta**: conexiones máximas, `statement_timeout` y sesiones de solo lectura para reportes.
5. **Conexiones cifradas** (`sslmode=require` o `verify-full`).
6. **Credenciales fuera del código**, en un gestor de secretos o en variables de entorno, rotadas si se exponen.
7. **Datos personales mínimos** (ISO 27002 8.10–8.12): no guardar lo que no se necesita. Abarrato no guarda datos de personas.

## 8. Operación  ·  ISO 27002 8.13 (respaldos), 8.15 y 8.16 (registros y monitoreo)

1. **Respaldos automáticos y prueba de restauración**: un respaldo que nunca se restauró no está probado. Neon ofrece restauración a un punto en el tiempo (*point-in-time restore*) y ramas.
2. **Entornos separados**: desarrollo, pruebas y producción (8.31). En Neon, una rama por entorno.
3. **Monitorear** el tamaño, las consultas lentas y las conexiones.
4. **Registrar quién cambió qué** en tablas críticas (auditoría), sin registrar datos sensibles.

## 9. Lista de verificación rápida

| ✔ | Pregunta | Abarrato |
|---|---|---|
| ☐ | ¿El dinero es entero o decimal exacto? | ✅ `integer` CLP |
| ☐ | ¿Las fechas tienen zona horaria? | ✅ `timestamptz` |
| ☐ | ¿Hay `NOT NULL` y `CHECK` en las reglas del dominio? | ✅ V1 y V10 |
| ☐ | ¿Todas las consultas son parametrizadas? | ✅ `JdbcTemplate` con `?` |
| ☐ | ¿Se escapan los comodines de `LIKE`? | ✅ |
| ☐ | ¿Las consultas que crecen tienen `LIMIT`? | ✅ 400 filas |
| ☐ | ¿Se evita `SELECT *` en el código? | 🟡 `RepositorioPrecios` lo usa en 4 consultas: pendiente listar las columnas |
| ☐ | ¿Hay migraciones versionadas y un script desde cero? | ✅ Flyway y `db/esquema.sql` |
| ☐ | ¿La aplicación usa una cuenta sin privilegios de dueño? | 🟡 Script listo (`db/roles.sql`), falta aplicarlo en Neon |
| ☐ | ¿La conexión es cifrada? | ✅ Neon exige SSL |
| ☐ | ¿Se probó restaurar un respaldo? | ☐ Pendiente: crear una rama de Neon desde un punto anterior |

## Fuentes

- Martin Kleppmann, *Designing Data-Intensive Applications, 2nd Edition*: <https://martin.kleppmann.com/2026/03/24/designing-data-intensive-applications-2e.html>
- Bill Karwin, *SQL Antipatterns, Volume 1* (2022): <https://www.oreilly.com/library/view/-/9798888650011/>
