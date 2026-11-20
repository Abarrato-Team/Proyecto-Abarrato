# Contrato de la API REST de Abarrato · v1

Base en producción: `https://abarrato-backend-850689460369.southamerica-west1.run.app`
Base local: `http://127.0.0.1:8080` (desde el emulador Android: `http://10.0.2.2:8080`)

Todas las rutas cuelgan de **`/api/v1/precios`**. El `v1` en la ruta es la versión del contrato: un cambio que rompa a los clientes (quitar o renombrar un campo, cambiar un tipo) va a `/api/v2`, y `/api/v1` se mantiene mientras la app publicada lo use. Agregar campos nuevos no rompe a nadie y se hace dentro de v1.

Las respuestas son JSON en UTF-8. Los precios son **enteros en pesos chilenos (CLP)**, nunca decimales. Las fechas usan ISO 8601 con zona horaria (`2026-11-13T09:15:00-03:00`).

## Recursos

| Verbo | Ruta | Para qué | Parámetros | Éxito | Errores |
|---|---|---|---|---|---|
| GET | `/tiendas` | Las 8 cadenas que compara la app | — | 200 | — |
| GET | `/tiendas/{comercio}` | Pasillos del catálogo web de una tienda | `comercio` en la ruta | 200 | 400 tienda inexistente |
| GET | `/tiendas/{comercio}/productos` | Una página de un pasillo | `pasillo` (obligatorio), `pagina` (≥1, por defecto 1) | 200 | 400 pasillo o página inválidos · 502 la tienda no respondió |
| GET | `/categorias` | Rubros para los filtros | — | 200 | — |
| GET | `/categorias/{rubro}` | Productos de un rubro en varias tiendas | `comercio` (opcional) | 200 | 400 rubro o tienda inexistente |
| GET | `/en-vivo` | Buscar un producto en las tiendas | `q` (2 a 80 caracteres, con letras o números), `comercio` o `comercios` (opcionales), `pagina` (≥1), `guardado` (`true` = responde al instante con la base de datos) | 200 | 400 búsqueda inválida |
| GET | `/catalogo` | Catálogo guardado en la base | `q` (≤120), `comercio` + `ubicacion` (juntos, para una sucursal) | 200 | 400 |
| GET | `/sucursales` | Sucursales físicas con precios guardados | `comercio` (opcional) | 200 | 400 tienda inexistente |
| GET | `/fotos` | Foto de cada producto | `clave` repetido, de 1 a 50 veces | 200 | 400 |
| GET | `/` (raíz) | Último precio guardado de un producto | `comercio`, `productoId`, `ubicacion` (obligatorios) | 200 | 400 · 404 sin precios guardados |
| POST | `/comparar-carrito` | Total del carrito en cada tienda | cuerpo JSON, ver abajo | 200 | 400 carrito inválido o JSON roto |
| POST | `/actualizar` | Recorrido diario de precios (solo Cloud Scheduler) | encabezado `X-Abarrato-Token` | 200 | 403 token incorrecto · 404 tarea desactivada |

Valores de `comercio`: `JUMBO`, `SANTA_ISABEL`, `LIDER`, `UNIMARC`, `TOTTUS`, `ALVI`, `CUGAT`, `DEL_PEDREGAL`.

Todas las rutas son de solo lectura (GET), salvo dos POST. `POST /comparar-carrito` no modifica nada: usa POST porque el carrito no cabe cómodo en la URL. `POST /actualizar` sí escribe en la base, por eso exige el token. La API **no tiene recursos de usuario**: la lista de compras, la despensa y el historial viven solo en el teléfono (minimización de datos personales, ver `docs/DECISIONES.md`).

## Ejemplos

Buscar arroz en todas las tiendas:

```http
GET /api/v1/precios/en-vivo?q=arroz%20grado%201
```

```json
{
  "productos": [
    {
      "nombre": "Arroz grado 1 Tucapel 1 kg",
      "claveCarrito": "gtin:07801234000000",
      "urlImagen": "https://…/arroz.jpg",
      "ofertas": [
        { "comercio": "UNIMARC", "productoId": "123", "precioClp": 1690, "precioOfertaClp": 1490,
          "condicionOferta": "Con Club Unimarc", "precioAyerClp": 1690, "vigente": true,
          "observadoEn": "2026-11-13T09:15:00-03:00", "urlFuente": "https://www.unimarc.cl/…" },
        { "comercio": "JUMBO", "productoId": "456", "precioClp": 1750, "vigente": true, "…": "…" }
      ]
    }
  ],
  "fuentes": [ { "comercio": "JUMBO", "estado": "RECIENTE", "observadoEn": "2026-11-13T09:15:00-03:00" } ],
  "paginas": 1,
  "hayMas": false
}
```

*(Valores de ejemplo, respuesta abreviada.)* `estado` de cada fuente: `RECIENTE` (leída ahora), `ULTIMA_CAPTURA` (la tienda no respondió y se usa la última lectura guardada) o `NO_DISPONIBLE`. `precioAyerClp` permite mostrar "bajó desde ayer".

Comparar un carrito:

```http
POST /api/v1/precios/comparar-carrito
Content-Type: application/json

{ "productos": [ { "clave": "gtin:07802900001308", "cantidad": 2, "busqueda": "leche entera" } ] }
```

Con `sucursales: [{ "comercio": "JUMBO", "ubicacion": "CODIGO" }]` se compara contra sucursales físicas en vez de la web.

## Errores: un solo formato para todas las rutas

Cualquier error responde con el código HTTP correcto y este cuerpo:

```json
{
  "estado": 400,
  "codigo": "SOLICITUD_INVALIDA",
  "mensaje": "La búsqueda debe tener entre 2 y 80 caracteres",
  "ruta": "/api/v1/precios/en-vivo",
  "fecha": "2026-11-13T10:02:11-03:00"
}
```

- `codigo` es estable y lo leen los programas; `mensaje` está en español y la app lo muestra tal cual al usuario.
- Nunca se envían trazas, SQL ni nombres de clases. El detalle de los errores 500 queda solo en el log de Cloud Run.

| HTTP | `codigo` | Cuándo |
|---|---|---|
| 400 | `SOLICITUD_INVALIDA` | Un dato no cumple las reglas (largo, rango, combinación de parámetros) |
| 400 | `PARAMETRO_FALTANTE` | Falta un parámetro obligatorio |
| 400 | `VALOR_INVALIDO` | Tipo incorrecto, por ejemplo `comercio=FALABELLA` (el mensaje lista los válidos) |
| 400 | `JSON_INVALIDO` | El cuerpo del POST no es JSON |
| 403 | `ACCESO_DENEGADO` | Token de la tarea diaria ausente o incorrecto |
| 413 | `CUERPO_DEMASIADO_GRANDE` | El cuerpo del POST supera 64 KB |
| 429 | `DEMASIADAS_PETICIONES` | Se pasó el límite por minuto; el encabezado `Retry-After` dice cuántos segundos esperar |
| 404 | `NO_ENCONTRADO` | No hay datos para lo pedido, o la ruta no existe |
| 405 | `METODO_NO_PERMITIDO` | Verbo equivocado (por ejemplo, GET a `/comparar-carrito`) |
| 502 | `TIENDA_INACCESIBLE` | La página del supermercado no respondió |
| 500 | `ERROR_INTERNO` | Falla inesperada del servidor |

Implementación: [`ManejadorErrores.java`](../backend/src/main/java/cl/abarrato/precios/ManejadorErrores.java). Pruebas del contrato: [`ContratoApiTest.java`](../backend/src/test/java/cl/abarrato/precios/ContratoApiTest.java).

## Límite de peticiones

Para que nadie sature el servidor ni haga que los supermercados bloqueen a Abarrato, cada cliente (IP) tiene un cupo por minuto:

| Qué | Por defecto | Variable |
|---|---|---|
| Peticiones en total, por cliente | 300 | `ABARRATO_LIMITE_POR_CLIENTE` |
| Peticiones "costosas" por cliente (las que visitan los supermercados: `/en-vivo` sin `guardado`, `/comparar-carrito`, `/categorias/{rubro}`, `/tiendas/{comercio}/productos`) | 60 | `ABARRATO_LIMITE_BUSQUEDAS` |
| Peticiones costosas de todos los clientes juntos | 600 | `ABARRATO_LIMITE_TOTAL` |

Cada respuesta trae `X-RateLimit-Limit` y `X-RateLimit-Remaining`. Al pasarse, la API responde `429` con `Retry-After`. `POST /actualizar` no tiene límite porque exige su token.

## Probar la API

Importa [`docs/Abarrato.postman_collection.json`](Abarrato.postman_collection.json) en Postman (o Bruno, que lee el mismo formato). La variable `baseUrl` apunta a Cloud Run; cámbiala a `http://127.0.0.1:8080` para probar el servidor local. La colección incluye un caso exitoso y uno de error por recurso.

Con curl:

```bash
curl -i "https://abarrato-backend-850689460369.southamerica-west1.run.app/api/v1/precios/en-vivo?q=a"
# → HTTP/2 400 y el JSON de error
```
