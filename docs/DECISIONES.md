# Registro de decisiones de Abarrato

Cada entrada dice qué se decidió, por qué y qué se dejó de lado. Las más nuevas van arriba.

## D13 · "¿Qué puedo cocinar?" completo y calculado en el teléfono (noviembre 2026)

**Decisión.** La sección pasó de 10 recetas con solo el nombre a 19 recetas caseras chilenas, cada una con tiempo, porciones, ingredientes obligatorios y opcionales, y pasos de preparación. Desde cada receta, el botón "Buscar …" lleva a comparar el precio de lo que falta en los 8 supermercados.
**Cómo se aplicaron los protocolos del proyecto.**
- **Privacidad (D2, ISO 27002 8.11):** la despensa no sale del teléfono; las recetas se calculan localmente.
- **Corrección funcional (ISO 25010):** el reconocimiento de ingredientes usa palabras completas y excepciones. Así, "papaya" ya no cuenta como papa, la "pasta dental" no cuenta como fideos y la "salsa de tomate" no sirve para una ensalada.
- **Estructuras de datos:** las palabras de cada producto se guardan en un `HashSet`, para consultarlas en O(1).
- **Inclusividad (ISO 25010):** cada tarjeta tiene una descripción para TalkBack y las secciones están marcadas como encabezados.
- **QA:** `RecetasTest` tiene 12 pruebas de caja negra y blanca, sin emulador (`gradlew :app:testDebugUnitTest`).

**Descartado.** Guardar las recetas en el servidor o usar una API de recetas externa: obligaría a enviar la despensa fuera del teléfono y a depender de la red para una función que funciona bien sin ella.

## D12 · Normas de calidad y seguridad como guía del proyecto (noviembre 2026)

**Decisión.** El proyecto se evalúa con ISO/IEC 25010:2023 (calidad), ISO/IEC 27001/27002:2022 (codificación segura y perfiles de acceso) y OWASP Top 10:2025, API Top 10:2023 y Mobile Top 10:2024 (seguridad). Las guías están en `docs/calidad/`, escritas para que también sirvan de contexto a una IA.
**Cambios que trajo.**
- Pruebas de caja negra, blanca y gris (`CajaNegraTest`, `CajaBlancaTest`, `CajaGrisTest`).
- Perfiles de base de datos (`db/roles.sql`) y script para crear la base desde cero (`db/esquema.sql`).
- Restricciones `CHECK` (V10).
- Contenedor sin root, IP validada antes de registrarla en el log y `allowBackup="false"` en la app.

**Pendientes conocidos.** Ver la sección 5 de `docs/calidad/06-OWASP-2025.md`: suspender el backend de Render, aplicar los perfiles en Neon, migrar a Spring Boot 4.x y configurar alertas.
**Por qué Spring Boot 3.5 sigue por ahora.** Su soporte gratuito terminó en junio de 2026, pero pasar a 4.x cambia muchas dependencias y podría romper la app antes de la entrega. Se hará en una rama aparte, con las pruebas como red de seguridad.

## D11 · Medidas de seguridad de la API y la app (noviembre 2026)

**Decisión.** Se agregaron, sin cambiar la arquitectura (Android → Spring Boot → PostgreSQL):
1. **Límite de peticiones** (`LimitePeticiones`, un filtro de Spring Boot): cupo por minuto por IP y uno total para las búsquedas que visitan los supermercados. Al pasarse responde 429 con `Retry-After`.
2. **Tamaño máximo del cuerpo**: 64 KB (413).
3. **Cabeceras de seguridad** en todas las respuestas (`CabecerasSeguridad`): `nosniff`, `X-Frame-Options: DENY`, `Content-Security-Policy`, `Strict-Transport-Security`. Sin cabeceras CORS.
4. **Solo HTTPS en la app**: `usesCleartextTraffic="false"`; la versión de depuración permite únicamente `10.0.2.2`.
5. **Errores sin detalles internos**: `include-stacktrace: never` además del manejador central (D10).
**Por qué.** La API no tiene usuarios ni datos personales, así que el riesgo principal no es el robo de datos sino el abuso: cada búsqueda en vivo dispara visitas a 8 sitios. Sin límite, un script podría tumbar el servidor, subir el costo de Cloud Run o hacer que los supermercados bloqueen la IP de Abarrato.
**Limitación conocida.** La IP se toma del encabezado `X-Forwarded-For`, que un atacante puede falsificar para saltarse el cupo por cliente; por eso existe además el cupo total. Los contadores viven en la memoria de cada instancia de Cloud Run (máximo 2), así que el límite real puede ser hasta el doble. Para algo más estricto habría que guardar los contadores en una base compartida o usar Cloud Armor.
**Descartado.** Usuarios con contraseña y JWT: la app no tiene cuentas (D2), y agregar login solo para buscar precios empeora la experiencia sin proteger ningún dato.

## D10 · Errores con un formato único y manejador central (noviembre 2026)

**Decisión.** Todos los errores de la API responden `{estado, codigo, mensaje, ruta, fecha}` desde una sola clase, `ManejadorErrores` (`@RestControllerAdvice`). Los controladores y servicios solo lanzan excepciones con nombre propio: `SolicitudInvalidaException` (400), `RecursoNoEncontradoException` (404) y `AccesoDenegadoException` (403).
**Por qué.** Antes cada ruta respondía a su manera: algunas un 400 sin cuerpo, otras `{"error": …}` y otras la página de error de Spring. La app no podía explicarle al usuario qué pasó. Ahora la app muestra el `mensaje` del servidor tal cual.
**Descartado.** Usar `ProblemDetail` (RFC 9457) de Spring: es un estándar válido, pero sus campos están en inglés y el curso pide mensajes en el idioma del usuario. Si en el futuro otra empresa consume la API, se puede migrar sin cambiar los códigos.

## D9 · Controlador flaco: la capa de presentación no toca la base de datos (noviembre 2026)

**Decisión.** `ControladorPrecios` ya no recibe `RepositorioPrecios`. Las reglas (máximo 50 fotos, sucursal con comercio y ubicación juntos, qué pasa si no hay precio) pasaron a `ServicioPrecios`.
**Por qué.** Separación en capas: presentación → negocio → datos. Así las reglas se prueban sin HTTP y el controlador se lee como un índice de la API.
**Pendiente.** Todas las clases siguen en un solo paquete Java (`cl.abarrato.precios`). Separarlas en carpetas `controlador/`, `servicio/` y `repositorio/` obliga a volver públicas muchas clases que hoy están protegidas por ser del paquete; se dejó para cuando haya tiempo de probar el cambio completo.

## D8 · Configuración y secretos fuera del código

**Decisión.** La clave de la base de datos (Neon) y el token de la tarea diaria solo existen como variables de entorno en Cloud Run (`ABARRATO_DB_PASSWORD`, `ABARRATO_TOKEN_TAREA`). Los valores ajustables también son variables: `ABARRATO_TAREA_MINUTOS` (25), `ABARRATO_TAREA_PAGINAS` (400), `ABARRATO_MAX_WEB_AGE` (PT15M).
**Por qué.** Un secreto en el repositorio queda en el historial de Git para siempre. Las veces que una clave apareció en un chat o en una salida de consola, se cambió.

## D7 · Mostrar primero lo guardado y después lo vivo (método B)

**Decisión.** La app pide dos búsquedas en paralelo: `guardado=true` responde en menos de un segundo desde PostgreSQL, y la búsqueda en vivo reemplaza el resultado cuando llega. El aviso dice solo "Actualizando…".
**Por qué.** El raspado de 8 tiendas tarda de 5 a 20 segundos; el usuario no debe mirar una pantalla vacía.

## D6 · Tarea diaria con Cloud Scheduler en vez de un `@Scheduled`

**Decisión.** Cloud Scheduler llama a `POST /api/v1/precios/actualizar` a las 06:00 (hora de Chile) con un token.
**Por qué.** Cloud Run apaga el servidor cuando no hay visitas (mínimo 0 instancias); un temporizador interno no correría. Guardar un precio por día permite mostrar "bajó desde ayer".
**Detalle.** El token se compara con `MessageDigest.isEqual` (tiempo constante) y sin token configurado la ruta responde 404.

## D5 · El precio más barato considera la oferta

**Decisión.** El precio que se compara es el menor entre el normal y el de oferta (incluidas las condiciones como "Con Club Unimarc"), y la ficha muestra la condición.
**Por qué.** Mostrar el precio normal hacía que la app recomendara la tienda equivocada.

## D4 · Ahorro en el supermercado y gasto en transporte por separado

**Decisión.** La confirmación muestra dos números: "Ahorras en el supermercado" y "Gastas en transporte", más el total.
**Por qué.** Restar el transporte al ahorro daba $0 con frecuencia y el usuario no entendía de dónde salía.

## D3 · Backend en Google Cloud Run (Santiago) y base en Neon

**Decisión.** Cloud Run en `southamerica-west1` con PostgreSQL administrado en Neon. Render quedó como respaldo antiguo.
**Por qué.** Menor latencia desde Chile, arranque en frío más corto que el plan gratuito de Render y escala a cero (sin costo cuando nadie usa la app).

## D2 · Datos personales solo en el teléfono

**Decisión.** La lista de compras, la despensa, el historial y los ajustes de traslado se guardan en `SharedPreferences` del teléfono. El servidor no tiene usuarios ni guarda ubicaciones.
**Por qué.** Minimización de datos: el servidor solo necesita precios públicos. Si se filtrara la base, no hay datos de personas.

## D1 · Java nativo para Android y Spring Boot para el servidor

**Decisión.** Android en Java sin frameworks extra; servidor Spring Boot 3 con Java 17, JDBC y Flyway.
**Por qué.** Es el lenguaje del curso, el equipo lo conoce y Spring Boot resuelve rutas, inyección de dependencias, conexión a la base y migraciones sin escribirlo a mano.
