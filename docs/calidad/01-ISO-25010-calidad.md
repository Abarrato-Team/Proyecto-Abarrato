# ISO/IEC 25010:2023 · Calidad del producto aplicada a Abarrato

> **Para usar con IA:** pega este archivo como contexto y pide: *"Evalúa este cambio de código contra las 9 características de ISO/IEC 25010:2023 descritas aquí y dime cuáles mejora o empeora"*.

## Qué es

ISO/IEC 25010 es la norma internacional que define **qué significa que un software sea "de calidad"**. No dice cómo programar: entrega un vocabulario común para pedir, medir y evaluar calidad. La versión vigente es la **segunda edición, de noviembre de 2023**, que reemplazó a la de 2011. Pertenece a la familia SQuaRE (ISO/IEC 25000).

Cambios de la edición 2023 respecto de 2011:

- Pasa de 8 a **9 características**: se agrega **Seguridad física (Safety)**.
- **Usabilidad** pasa a llamarse **Capacidad de interacción**, y **Portabilidad** pasa a llamarse **Flexibilidad**.
- Aparecen subcaracterísticas nuevas, como *inclusividad*, *autodescripción*, *resistencia* (dentro de seguridad) y *escalabilidad* (dentro de flexibilidad).

## Las 9 características y cómo las cumple Abarrato

| # | Característica | Pregunta que responde | Evidencia en Abarrato | Estado |
|---|---|---|---|---|
| 1 | **Adecuación funcional** | ¿Hace lo que tiene que hacer, completo y correcto? | Compara 8 supermercados, detecta ofertas y "bajó desde ayer", agrupa productos iguales (`EquivalenciaProductos`, 24 casos de prueba) | ✅ |
| 2 | **Eficiencia de desempeño** | ¿Responde rápido sin gastar de más? | Respuesta inmediata con precios guardados (método B) y luego en vivo; índices `pg_trgm` y por ubicación (V7); `LIMIT 400` | ✅ |
| 3 | **Compatibilidad** | ¿Convive e intercambia datos con otros sistemas? | API REST JSON versionada (`/api/v1`), contrato en `docs/API.md`, colección Postman | ✅ |
| 4 | **Capacidad de interacción** | ¿Se entiende y se usa fácil, incluso con errores? | Mensajes en español del servidor, aviso "Actualizando…", validación antes de buscar, estados de carga, vacío y error | 🟡 Falta revisar contraste y lector de pantalla (inclusividad) |
| 5 | **Fiabilidad** | ¿Funciona sin fallar y se recupera? | Si una tienda no responde, usa la última captura (`ULTIMA_CAPTURA`); la tarea diaria es idempotente (`guardarSiCambio`) | ✅ |
| 6 | **Seguridad** | ¿Protege la información y resiste abusos? | HTTPS, límite de peticiones (429), cabeceras, token de la tarea en tiempo constante, perfiles de base de datos (`db/roles.sql`), contenedor sin root | ✅ (ver `06-OWASP-2025.md`) |
| 7 | **Mantenibilidad** | ¿Es fácil de cambiar sin romper nada? | Capas controlador → servicio → repositorio; 10 clases de prueba (61 métodos); CI con GitHub Actions; registro de decisiones | 🟡 Todo el backend está en un solo paquete Java (D9) |
| 8 | **Flexibilidad** | ¿Se adapta a otros entornos o a más carga? | Contenedor Docker; configuración por variables de entorno; Cloud Run escala de 0 a 2 instancias | ✅ |
| 9 | **Seguridad física (Safety)** | ¿Puede dañar a personas, bienes o el entorno? | La app no controla equipos; el riesgo es económico (mostrar un precio equivocado). Se mitiga mostrando la hora de cada precio y el enlace a la tienda | ✅ (riesgo bajo) |

## Las 40 subcaracterísticas, una por una

La norma divide las 9 características en **40 subcaracterísticas**. En la tabla, ✅ significa que cumple, 🟡 que cumple en parte y ➖ que no aplica a Abarrato. Las definiciones están resumidas con palabras propias a partir del texto de la norma (sección 3).

| Característica | Subcaracterística | Qué significa | Abarrato | |
|---|---|---|---|---|
| **1. Adecuación funcional** | Completitud funcional | Las funciones cubren todas las tareas y objetivos de los usuarios | Buscar, comparar 8 tiendas, lista, despensa, plan de compra con transporte | ✅ |
| | Corrección funcional | Los resultados son exactos | El precio más bajo considera ofertas; pesos enteros sin redondeos; `ExtractorPreciosTest` | ✅ |
| | Pertinencia funcional | Solo los pasos necesarios para lograr la tarea | Agregar a la lista en un toque; "Optimizar" calcula el plan de una vez | ✅ |
| **2. Eficiencia de desempeño** | Comportamiento temporal | Tiempos de respuesta y volumen dentro de lo requerido | Precios guardados en < 1 s y luego en vivo | ✅ |
| | Utilización de recursos | No usa más CPU, memoria o red de lo necesario | Caché LRU de 40 búsquedas; Cloud Run escala a 0 | ✅ |
| | Capacidad | Cumple los límites máximos (usuarios, datos) | `LIMIT 400`, 600 búsquedas por minuto en total, máximo 2 instancias | ✅ |
| **3. Compatibilidad** | Coexistencia | Funciona compartiendo recursos sin perjudicar a otros | Pausa de 400 ms entre páginas para no saturar a las tiendas | ✅ |
| | Interoperabilidad | Intercambia información y la usa | API REST JSON v1; lee HTML y JSON de 8 tiendas; mapas OSM | ✅ |
| **4. Capacidad de interacción** | Reconocimiento de pertinencia | El usuario reconoce rápido que le sirve | La pantalla de inicio muestra el gasto y el ahorro de la semana | ✅ |
| | Aprendizaje | Se aprende a usar en poco tiempo | Cuatro pestañas simples; manual con datos de prueba | ✅ |
| | Operabilidad | Fácil de operar y controlar | Botones grandes; se puede elegir la tienda de cada producto | ✅ |
| | Protección contra errores del usuario | Evita errores de operación | No busca con menos de 2 letras y explica por qué | ✅ |
| | Compromiso del usuario | La interfaz invita a usarla (antes "estética") | Logos de las tiendas, colores por cadena, etiquetas de oferta | ✅ |
| | Inclusividad | La pueden usar personas con distintas capacidades | **Pendiente**: revisar contraste, tamaño de letra y TalkBack | 🟡 |
| | Asistencia al usuario | Ayuda a lograr la tarea a usuarios diversos | Mensajes claros; "Actualizando…"; explicación del ahorro | 🟡 |
| | Autodescripción | La interfaz explica por sí misma qué hace cada cosa | Etiquetas como "Elegido: tienda" o "↓ Bajó $X desde ayer" | ✅ |
| **5. Fiabilidad** | Ausencia de fallos | Funciona sin fallar en uso normal (antes "madurez") | 61 métodos de prueba y CI | ✅ |
| | Disponibilidad | Está disponible cuando se necesita | Cloud Run gestionado; arranque en frío de unos segundos | ✅ |
| | Tolerancia a fallos | Sigue funcionando aunque falle una parte | Si una tienda cae, usa la última captura (`ULTIMA_CAPTURA`) | ✅ |
| | Recuperabilidad | Recupera los datos y el estado tras una falla | Respaldo de Neon a un punto en el tiempo; tarea diaria repetible | 🟡 Restauración aún no probada |
| **6. Seguridad** | Confidencialidad | Los datos solo los ve quien debe | Sin datos personales en el servidor; HTTPS | ✅ |
| | Integridad | Impide cambios no autorizados | Solo la tarea con token escribe; `CHECK` en la base (V10) | ✅ |
| | No repudio | Las acciones se pueden probar después | Cada precio guarda su hora y la URL de origen | ✅ |
| | Responsabilidad (rendición de cuentas) | Se sabe quién hizo cada acción | Logs de Cloud Run; perfiles de base de datos separados | 🟡 Falta aplicar `db/roles.sql` |
| | Autenticidad | Se puede comprobar que alguien es quien dice ser | Token de Cloud Scheduler; certificados TLS | ✅ |
| | Resistencia (nueva) | Sigue operando bajo ataque | Límite de peticiones (429), cuerpo máximo (413) | ✅ |
| **7. Mantenibilidad** | Modularidad | Cambiar una parte afecta poco a las demás | Controlador, servicio y repositorio separados | 🟡 Un solo paquete Java |
| | Reusabilidad | Las partes se pueden usar en otro sistema | `EquivalenciaProductos` y `ExtractorPrecios` no dependen de Spring | ✅ |
| | Analizabilidad | Es fácil encontrar la causa de un problema | Logs con la tienda y la causa; formato de error con `codigo` | ✅ |
| | Modificabilidad | Se puede cambiar sin introducir defectos | Migraciones Flyway; configuración por variables | ✅ |
| | Testeabilidad | Se puede probar con facilidad | Pruebas sin base de datos ni internet (Mockito y MockMvc) | ✅ |
| **8. Flexibilidad** | Adaptabilidad | Se adapta a otros entornos | Docker; corre en Cloud Run, en Render o en local | ✅ |
| | Escalabilidad (nueva) | Soporta más carga agregando recursos | Cloud Run escala por instancias; Neon escala la base | ✅ |
| | Instalabilidad | Se instala con facilidad | Un comando de Gradle y otro de gcloud (`docs/MANUAL.md`) | ✅ |
| | Reemplazabilidad | Puede reemplazar a otro producto | Se pasó de Render a Cloud Run sin cambiar la app (solo la URL) | ✅ |
| **9. Seguridad física (Safety)** | Restricción operativa | Limita la operación a estados seguros | Ante una falla no se inventa un precio: se muestra la última captura con su hora | ✅ |
| | Identificación de riesgos | Identifica eventos que pueden causar daño | Riesgo económico (un precio desactualizado) identificado en D11 y D12 | ✅ |
| | A prueba de fallos | Ante una falla queda en modo seguro | Token vacío deja la tarea desactivada (404); errores sin detalles | ✅ |
| | Advertencia de peligro | Avisa de riesgos inaceptables | Avisos de "precios guardados" frente a "en vivo", con la hora | ✅ |
| | Integración segura | Se integra con otros productos sin perder seguridad | Solo dominios fijos de las tiendas; WebView con lista de dominios permitidos | ✅ |

**Resultado:** 35 de 40 subcaracterísticas cumplidas, 5 parciales (inclusividad, asistencia al usuario, recuperabilidad, responsabilidad y modularidad) y ninguna sin cumplir.

## Cómo medir (métricas sugeridas)

| Característica | Métrica | Meta | Cómo se obtiene |
|---|---|---|---|
| Eficiencia | Tiempo hasta el primer resultado | < 1,5 s | Registro de la app o `curl -w "%{time_total}"` con `guardado=true` |
| Fiabilidad | Tiendas respondiendo en la tarea diaria | ≥ 7 de 8 | Línea "Actualización diaria lista" en Cloud Run |
| Mantenibilidad | Pruebas que pasan en CI | 100 % | GitHub Actions (`pruebas.yml`) |
| Seguridad | Hallazgos OWASP abiertos de severidad alta | 0 | `06-OWASP-2025.md` |
| Adecuación | Productos agrupados correctamente | ≥ 95 % de una muestra de 100 | Revisión manual de búsquedas |

## Fuentes

- ISO/IEC 25010:2023, segunda edición (vista previa de 11 páginas aportada por el equipo: prólogo, alcance y definiciones 3.1 a 3.4.4). Las demás subcaracterísticas se tomaron de resúmenes públicos de la norma.
- ISO, ficha de ISO/IEC 25010:2023: <https://www.iso.org/standard/78176.html>
- arc42 Quality Model, resumen de los cambios de 2023: <https://quality.arc42.org/articles/iso-25010-update-2023>
