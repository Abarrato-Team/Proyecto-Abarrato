    q# Backend de Abarrato

Java 17, Spring Boot, Maven y PostgreSQL. Flyway crea la tabla de observaciones al iniciar.

## Ejecución local

Crea una base PostgreSQL y define `ABARRATO_DB_URL`, `ABARRATO_DB_USER` y `ABARRATO_DB_PASSWORD`. La URL tiene el formato `jdbc:postgresql://localhost:5432/abarrato`. Para separar los permisos de migración, define también `ABARRATO_MIGRATION_USER` y `ABARRATO_MIGRATION_PASSWORD` y otorga al usuario de aplicación acceso al esquema y permisos SELECT e INSERT sobre la tabla.

```powershell
mvn clean verify
java -jar target/backend-0.1.0-SNAPSHOT.jar --spring.main.web-application-type=none --abarrato.importar=precios.tsv
java -jar target/backend-0.1.0-SNAPSHOT.jar
```

## Consulta de precios desde la app

Desde la carpeta del backend, puedes iniciar la API con `mvn spring-boot:run` después de configurar PostgreSQL. Mantén el servidor en el puerto `8080` para el emulador. En otra terminal PowerShell, prueba:

```powershell
Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/precios/en-vivo?q=leche%20soprole%20entera'
```

La consulta descarga los catálogos públicos configurados, guarda las observaciones con su hora y agrupa las ofertas con una identidad compartida verificada. Reutiliza una captura reciente durante dos minutos y marca la última lectura en caché si falla una renovación. La respuesta distingue el estado de cada comercio y conserva el enlace a la fuente. Añade `&comercio=TOTTUS` para limitar la búsqueda a esa cadena, o `&comercios=JUMBO,SANTA_ISABEL,LIDER,DEL_PEDREGAL` para varias (así consulta la app Android). Puedes definir `ABARRATO_SCRAPE_ENABLED=false` para mantener solo las consultas bajo demanda.

El catálogo usa páginas de Jumbo, Santa Isabel, Líder, Unimarc, Tottus, Alvi, Del Pedregal y Cugat. Los precios se registran como `ONLINE_SIN_UBICACION` y corresponden a productos individuales, en CLP. Las ofertas se agrupan cuando sus identificadores permiten confirmar la coincidencia. Cada grupo devuelve `claveCarrito` y, si está publicada, `urlImagen`.

Jumbo y Santa Isabel aportan resultados de su buscador público. Líder, Tottus y Cugat también aportan resultados de categorías como arroz, pastas, aceites, huevos y detergentes. La API limita cada respuesta a 100 grupos y reutiliza las capturas recientes de cada página. Para recuperar la foto de un producto guardado, consulta `GET /api/v1/precios/fotos?clave=gtin:07802900001308`.

`GET /api/v1/precios/tiendas` lista las cadenas y `GET /api/v1/precios/tiendas/{comercio}` devuelve sus pasillos y su enlace oficial. Con `GET /api/v1/precios/tiendas/{comercio}/productos?pasillo=despensa&pagina=1` se recorren las páginas públicas de un pasillo. El campo `siguiente` indica si se puede avanzar. La cobertura interna depende de las páginas con precios verificables de cada tienda. Del Pedregal ofrece su vitrina y el acceso a su colección oficial.

`GET /api/v1/precios/categorias` lista los rubros para explorar varias tiendas. `GET /api/v1/precios/categorias/HELADO` combina las primeras páginas públicas configuradas para el rubro y admite `?comercio=TOTTUS` para ver una cadena. Los pasillos de cada tienda permiten seguir recorriendo páginas.

Cada oferta devuelve su `comercio` y `productoId`. Si una línea de `/comparar-carrito` incluye esos dos campos, la respuesta agrega `compraElegida` con el desglose exacto por tienda. `opciones` compara el costo de llevar todas las cantidades a una sola cadena. Las líneas de carritos anteriores siguen admitiendo solo `clave` y `cantidad`.

## Total y ahorro del carrito

Envía las claves devueltas por el catálogo junto con sus cantidades:

```powershell
$solicitud = @{ productos = @(@{ clave = 'gtin:07802900001308'; cantidad = 2 }) }
Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/precios/comparar-carrito' `
    -Method Post -ContentType 'application/json' -Body ($solicitud | ConvertTo-Json -Depth 5)
```

Sin el campo `sucursales`, la consulta renueva los catálogos web y compara sus observaciones vigentes. Con `sucursales`, consulta exclusivamente el par `comercio` y `ubicacion` de cada entrada. `GET /api/v1/precios/sucursales` lista las ubicaciones registradas y `GET /api/v1/precios/catalogo?comercio=JUMBO&ubicacion=CODIGO&q=leche` permite buscar sus productos.

Cada línea puede incluir la `busqueda` de catálogo con la que se encontró el producto. En una comparación web se reutilizan o renuevan hasta seis búsquedas distintas junto con las vitrinas.

El total suma precio unitario por cantidad para un solo comercio y ubicación. Si falta un precio vigente, `totalClp` es `null`. Con dos o más carritos completos, el ahorro es la diferencia frente al carrito completo más caro. La vigencia por defecto es de 15 minutos para precios web y 24 horas para sucursal. Se puede ajustar con `ABARRATO_MAX_WEB_AGE` y `ABARRATO_MAX_BRANCH_AGE` en formato ISO 8601, como `PT15M` y `PT24H`.

## Observaciones en texto

Usa TSV en UTF-8 con la cabecera siguiente. Cada fila registra un producto en una ubicación y un instante. Para un precio web sin ubicación verificada, usa `ONLINE_SIN_UBICACION`. El precio conserva su texto publicado, por ejemplo `$2.390`, y se normaliza a `2390` CLP. Registra solo precios individuales en CLP. Las promociones condicionales y la disponibilidad requieren campos propios antes de usarlas para comparar.

```tsv
comercio	producto_id	ubicacion	nombre	precio_texto	url_fuente	observado_en
DEL_PEDREGAL	golden-chicken-tradicional-300-grs-sadia	ONLINE_SIN_UBICACION	Ejemplo de producto	$2.390	https://alimentosdelpedregal.com/products/golden-chicken-tradicional-300-grs-sadia	2026-11-07T18:00:00-03:00
```

La fila es ilustrativa. Para una captura real usa la hora observada y el producto y precio de la página. La consulta devuelve la observación más reciente para ese comercio, producto y ubicación.

Para importar precios por sucursal, usa una fuente que identifique el local y su código exacto en `ubicacion`. Puedes agregar la columna opcional `codigo_barras` al final de la cabecera y entregar un GTIN con dígito verificador válido. Eso permite comparar el mismo producto entre comercios. Conserva la cabecera de siete columnas para los archivos anteriores.

```text
GET /api/v1/precios?comercio=DEL_PEDREGAL&productoId=golden-chicken-tradicional-300-grs-sadia&ubicacion=ONLINE_SIN_UBICACION
```

## Actualización programada de precios

El bot está habilitado por defecto y se ejecuta cada tres horas a partir de las 00:00 de Chile continental (`America/Santiago`). Actualiza las páginas de partida y las fuentes configuradas para las categorías principales, entre ellas yogures, mantequillas, cereales, avena y café. Las demás páginas se consultan al navegar y el cálculo del carrito conserva su verificación de vigencia. Ejecuta una sola instancia con el bot habilitado. Puedes ajustar el horario con `ABARRATO_SCRAPE_CRON` usando la sintaxis Spring de seis campos.

| Comercio | Página de partida |
| --- | --- |
| Jumbo | Categoría Leches |
| Alvi | Especial Almacenero |
| Santa Isabel | Categoría Leches |
| Tottus | Categoría Leches Enteras |
| Líder | Vitrina Supermercado |
| Cugat | Categoría Arroz, Legumbres y Semillas |
| Alimentos Del Pedregal | Productos destacados en portada |
| Unimarc | Categoría Leches Blancas |

Las capturas web se guardan bajo `ONLINE_SIN_UBICACION`. En Alvi se conserva el valor unitario base del dato publicado en la página, sin las escalas por cantidad. Las otras fuentes conservan el precio extraído del catálogo. Cada lote se guarda en una transacción. Si falla una fuente, el bot continúa y la API puede mostrar la última lectura que conserva en caché.

Para operar desde una IP internacional, configura `ABARRATO_PROXY_HOST` y `ABARRATO_PROXY_PORT` con un proxy HTTP autorizado que admita CONNECT para HTTPS. Comprueba el país de salida antes de activarlo. El país de la IP no determina la sucursal.

La API escucha en localhost por defecto.
