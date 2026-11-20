# QA · Pruebas de caja blanca, caja negra y caja gris (con prompts para IA)

> Este archivo tiene dos partes: **qué es cada tipo de prueba y cómo se aplicó en Abarrato**, y **prompts listos para copiar** en una IA, para generar pruebas nuevas y, al final, pedir el informe en texto o HTML.

## 1. Las tres cajas

| | Caja negra | Caja blanca | Caja gris |
|---|---|---|---|
| **Qué se mira** | Solo el contrato: entradas y salidas | El código por dentro | El contrato **más** el diseño interno (capas, esquema, SQL) |
| **Quién la diseña** | Alguien que no programó la función, o el cliente | El programador | Un tester con acceso a la arquitectura |
| **Técnicas** | Particiones de equivalencia, valores límite, tablas de decisión, casos de uso | Cobertura de sentencias, de ramas y de caminos; condiciones múltiples | Pruebas de integración, inyección, verificación de efectos internos |
| **Encuentra** | Requisitos mal cumplidos, mensajes de error malos | Ramas nunca probadas, código muerto, condiciones mal escritas | Fallas entre capas, datos que llegan mal a la base, seguridad |
| **En Abarrato** | `CajaNegraTest`, `ContratoApiTest`, `RecetasTest` (app) | `CajaBlancaTest`, `SeguridadTest`, `EquivalenciaProductosTest`, `RecetasTest` (app) | `CajaGrisTest`, `CatalogoEnVivoTest` |

### 1.1 Caja negra en Abarrato (`CajaNegraTest`)

- **Valores límite** del largo de la búsqueda (el contrato dice de 2 a 80): se prueban 1, 2, 3, 79, 80 y 81 caracteres. Los extremos 1 y 81 deben dar 400; el resto, 200.
- **Particiones de equivalencia** del texto: normal, con números, con tildes y eñe, solo espacios, solo símbolos y con caracteres HTML.
- **Tabla de decisión** de las tiendas: las 8 válidas responden 200 y una inexistente responde 400.
- El verbo equivocado responde 405 con el formato de error estándar.

### 1.2 Caja blanca en Abarrato (`CajaBlancaTest`)

Para `ServicioPrecios.buscarCatalogo` se dibujaron sus 4 decisiones (`if`) y se escribió una prueba por cada uno de los **5 caminos**:

```text
texto > 120 ──sí──► camino 1: error 400, la base no se toca
   │no
comercio y ubicación nulos ──sí──► camino 2: busca en la web
   │no
comercio nulo ──sí──► camino 3: error
   │no
¿ubicación es sucursal? ──no──► camino 4: error
   │sí
camino 5: busca en la sucursal
```

Lo mismo con `validarConsulta` (3 ramas), con `LimitePeticiones.cliente` y `pareceIp` (IP válida, encabezado malicioso con salto de línea, IPv6), y con el constructor de `ObservacionPrecio` (descarta ofertas incoherentes).

### 1.3 Caja gris en Abarrato (`CajaGrisTest`)

Se entra por la API, pero los casos se eligieron **sabiendo** cómo está hecho el sistema:

- Como el repositorio usa `LIKE`, se envía `100%_algodon` y se verifica que llegue intacto al repositorio, que es quien escapa los comodines.
- Se envía una **inyección SQL** (`leche'; DROP TABLE observaciones_precio; --`) y se comprueba que se trata como texto.
- Se comprueba que **las peticiones inválidas nunca llegan a PostgreSQL** (`verifyNoInteractions(repositorio)`).
- Como el servicio usa un `Set`, se verifica que las fotos repetidas se pidan **una sola vez** a la base.
- Conociendo las columnas de la tabla, se verifica que la respuesta **no exponga campos internos**.

### 1.4 Cómo correrlas

```powershell
cd C:\AbarratoNuevo\Abarrato\backend
mvn test                                   # todas
mvn test -Dtest=CajaNegraTest              # solo caja negra
mvn test -Dtest="Caja*Test"                # las tres cajas
```

Pruebas de la app (sin emulador):

```powershell
cd C:\AbarratoNuevo\Abarrato\android
.\gradlew.bat :app:testDebugUnitTest
```

GitHub Actions corre las pruebas del backend en cada push (`.github/workflows/pruebas.yml`).

---

## 2. Prompts para IA

> Copia el prompt, reemplaza lo que está entre `<…>` y adjunta los archivos indicados. Los prompts piden que la IA **no invente** clases ni métodos que no estén en el código adjunto.

### Prompt 1 · Generar pruebas de CAJA NEGRA

```text
Actúa como tester QA. Solo puedes usar el contrato de la API que adjunto (docs/API.md); NO mires
ni supongas el código interno.

Para la ruta <GET /api/v1/precios/en-vivo>:
1. Identifica cada parámetro y sus reglas (obligatorio, tipo, mínimo, máximo, valores permitidos).
2. Arma una tabla de PARTICIONES DE EQUIVALENCIA: clase válida / clase inválida, un valor representativo
   y el código HTTP esperado.
3. Arma una tabla de VALORES LÍMITE (justo antes, en el límite y justo después).
4. Escribe las pruebas en Java 17 con JUnit 5 + MockMvc (standaloneSetup + ManejadorErrores), con
   @ParameterizedTest y @CsvSource, en la clase CajaNegra<Nombre>Test del paquete cl.abarrato.precios.
5. Cada prueba debe verificar el código HTTP y, si es error, el campo "codigo" del JSON.
No inventes rutas ni campos que no estén en el contrato.
```

### Prompt 2 · Generar pruebas de CAJA BLANCA

```text
Actúa como tester QA con acceso al código. Adjunto <ServicioPrecios.java>.

Para el método <buscarCatalogo>:
1. Dibuja su grafo de flujo en texto (cada if es un nodo de decisión).
2. Calcula la complejidad ciclomática (decisiones + 1) y lista TODOS los caminos independientes.
3. Para cada camino, da los valores de entrada que lo recorren y el resultado esperado.
4. Escribe una prueba JUnit 5 por camino (usa Mockito para el repositorio), con el nombre
   caminoN_<descripcion>, y verifica con verify(...) qué método del repositorio se llamó o no.
5. Indica si alguna rama es imposible de alcanzar (código muerto).
Usa solo métodos que existan en el archivo adjunto.
```

### Prompt 3 · Generar pruebas de CAJA GRIS

```text
Actúa como tester QA de integración. Conoces la arquitectura (adjunto README.md con las capas,
db/esquema.sql y RepositorioPrecios.java), pero probarás SOLO entrando por la API HTTP.

1. Lista 5 riesgos que aparecen entre capas (por ejemplo: un dato que llega mal escapado a SQL,
   una validación que existe en la app pero no en el servidor, columnas internas expuestas en el JSON,
   datos repetidos que generan consultas de más, errores de la base que se filtran al cliente).
2. Para cada riesgo, diseña una prueba que entre por MockMvc y verifique el efecto INTERNO con
   Mockito (verify / verifyNoInteractions sobre RepositorioPrecios).
3. Incluye al menos un caso de inyección SQL y uno de comodines de LIKE.
4. Escribe la clase CajaGris<Tema>Test en Java 17 con JUnit 5.
```

### Prompt 4 · Revisión de seguridad con OWASP 2025

```text
Actúa como revisor de seguridad. Adjunto docs/calidad/06-OWASP-2025.md y <archivos .java>.
Revisa el código contra OWASP Top 10:2025, OWASP API Security Top 10:2023 y, si es código Android,
OWASP Mobile Top 10:2024. Para cada hallazgo entrega: categoría (por ejemplo A05:2025), archivo y
línea, por qué es un riesgo, severidad (alta/media/baja), el cambio de código exacto y la prueba JUnit
que demuestra que quedó corregido. Si algo cumple, dilo también. No inventes hallazgos sin evidencia
en el código.
```

### Prompt 5 · Al terminar el código: pedir el INFORME en texto o HTML

```text
Ya terminamos los cambios. Genera el INFORME DE CALIDAD de Abarrato como UN SOLO ARCHIVO <HTML
autocontenido | de texto plano .txt> listo para descargar, con estas secciones:

1. Resumen (5 líneas): qué se probó y el resultado general.
2. Resultados de pruebas: tabla con clase de prueba, tipo (caja blanca/negra/gris), cantidad de casos,
   aprobados y fallidos. Usa el resultado real de `mvn test` que pego abajo; no inventes números.
3. Evaluación ISO/IEC 25010:2023: las 9 características con estado (cumple / parcial / no cumple) y evidencia.
4. Seguridad: estado de OWASP Top 10:2025 (A01–A10) y controles ISO 27002 aplicados.
5. Pendientes priorizados (alta, media, baja), con responsable sugerido.
6. Declaración de uso de IA.

Si es HTML: un solo archivo con el CSS adentro, sin JavaScript ni recursos externos, que se vea bien al
imprimir. Entrégalo como archivo descargable, no pegado en el chat.

Resultado de mvn test:
<pega aquí las últimas 30 líneas de la salida de mvn test>
```

## 3. Plantilla de caso de prueba (para el informe)

| ID | Tipo | Requisito | Precondición | Entrada | Resultado esperado | Resultado obtenido | Estado |
|---|---|---|---|---|---|---|---|
| CN-01 | Caja negra | Búsqueda de 2 a 80 caracteres | API arriba | `q=a` | 400 `SOLICITUD_INVALIDA` | 400 | ✅ |
| CB-05 | Caja blanca | Buscar en sucursal | — | `JUMBO`, `SUCURSAL-1` | Llama a `buscarCatalogoSucursal` | Llamado | ✅ |
| CG-02 | Caja gris | Sin inyección SQL | — | `leche'; DROP TABLE…` | 200 y el texto llega intacto | 200 | ✅ |
