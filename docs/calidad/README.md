# Calidad, seguridad y QA de Abarrato

Guías en markdown pensadas para dos usos: que el equipo las lea y que se adjunten a una IA como contexto, para que revise o genere código siguiendo las mismas reglas.

| Archivo | Tema | Úsalo cuando… |
|---|---|---|
| [01-ISO-25010-calidad.md](01-ISO-25010-calidad.md) | Modelo de calidad ISO/IEC 25010:2023 (9 características) | Evalúes si un cambio mejora o empeora la calidad |
| [02-ISO-27001-27002-seguridad.md](02-ISO-27001-27002-seguridad.md) | Codificación segura (27002 8.28) y perfiles de cuentas y bases de datos | Escribas código nuevo o des acceso a alguien |
| [03-QA-pruebas-caja-blanca-negra-gris.md](03-QA-pruebas-caja-blanca-negra-gris.md) | Pruebas de caja blanca, negra y gris, más **prompts para IA** | Necesites pruebas nuevas o el informe final |
| [04-bases-de-datos-buenas-practicas.md](04-bases-de-datos-buenas-practicas.md) | Buenas prácticas de bases de datos para cualquier motor | Cambies el esquema o una consulta |
| [05-estructuras-de-datos.md](05-estructuras-de-datos.md) | Estructuras de datos, síntesis de 4 libros (2022–2026) | Elijas cómo guardar datos en memoria |
| [06-OWASP-2025.md](06-OWASP-2025.md) | OWASP Top 10:2025, API Top 10:2023 y Mobile Top 10:2024 | Revises la seguridad |
| [informe-calidad.html](informe-calidad.html) | Informe final para imprimir o entregar | Presentes el resultado |

Archivos relacionados en el resto del proyecto:

- `db/roles.sql`: perfiles de base de datos.
- `db/esquema.sql`: crea la base desde cero.
- `backend/src/test/.../Caja*Test.java`: las pruebas de las tres cajas.
