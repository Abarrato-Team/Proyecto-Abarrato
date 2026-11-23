# Abarrato

**Compara en segundos el precio de tu compra en 8 supermercados de Chile y te dice dónde te conviene comprar, contando lo que gastas en llegar.**

![Pruebas](https://github.com/Abarrato-Team/Proyecto-Abarrato/actions/workflows/pruebas.yml/badge.svg)

Proyecto semestral de INGT1003 Arquitectura de Desarrollo Móvil y Web · Universidad Mayor · 2.º semestre 2026.

## Link a Video de youtube.
<div align="center">
  <a href="https://youtube.com">
    <img src="https://jorgenkh.no" alt="Video promocional Abarrato" width="600">
  </a>
</div>

 Integrantes

| Nombre | Rol | Github personal | 
|---|---|---|
| Valentina Lara | Prototipador | [@seesawgi](https://github.com/seesawgi) |
| Matías Quezada | Documentador | [@schulermitangst](https://github.com/Schulermitangst) |
|Lucas Lara | Administrador | [@lucaslarafuentes-dotcom](https://github.com/lucaslarafuentes-dotcom)|
## Qué hace

- Busca un producto y muestra su precio en Jumbo, Santa Isabel, Líder, Unimarc, Tottus, Alvi, Cugat y Del Pedregal, con foto, ofertas ("Con Club Unimarc", "Llevando 3 o más") y si **bajó o subió desde ayer**.
- Arma la lista de compras y deja elegir en qué tienda llevar cada producto.
- Registra la despensa para no comprar lo que ya hay en casa.
- **¿Qué puedo cocinar?**: muestra 19 recetas caseras ordenadas según lo que tienes, con los pasos y un botón para comparar el precio de lo que falta.
- Con tu ubicación, calcula el mejor plan de compra en los supermercados a 2 km, y separa **cuánto ahorras en el súper** de **cuánto gastas en transporte** (a pie, auto o micro/metro).

## Arquitectura

```text
 Cliente                Presentación              Negocio                    Datos
┌──────────────┐  HTTPS ┌───────────────────┐    ┌───────────────────┐     ┌──────────────────┐
│ App Android  │ ─────► │ ControladorPrecios│ ─► │ ServicioPrecios   │ ─►  │ RepositorioPrecios│ ─► PostgreSQL (Neon)
│ (Java)       │  JSON  │ TareaDiaria       │    │ CatalogoEnVivo    │     │ (JDBC + Flyway)   │
│ lista,       │ ◄───── │ ManejadorErrores  │    │ ComparadorCarrito │     └──────────────────┘
│ despensa en  │        └───────────────────┘    │ EquivalenciaProd. │ ─► webs de los 8 supermercados (jsoup)
│ el teléfono  │          Google Cloud Run (Santiago)                       
└──────────────┘          ▲ Cloud Scheduler 06:00 → POST /actualizar
```

- **Presentación** (`ControladorPrecios`, `TareaDiaria`): solo traduce HTTP ↔ Java. No consulta la base ni arma errores.
- **Negocio** (`ServicioPrecios`, `CatalogoEnVivo`, `ComparadorCarrito`, `EquivalenciaProductos`): reglas de búsqueda, agrupación de productos iguales, ofertas, totales del carrito.
- **Datos** (`RepositorioPrecios` + migraciones Flyway `V1`…`V9`): única clase que escribe SQL.
- **Errores** (`ManejadorErrores`): todas las rutas responden el mismo JSON de error con un mensaje en español.

## Enlaces

- API en producción: <https://abarrato-backend-850689460369.southamerica-west1.run.app/api/v1/precios/tiendas>
- Contrato de la API (rutas, parámetros, códigos de estado): [docs/API.md](docs/API.md)
- Colección para Postman o Bruno: [docs/Abarrato.postman_collection.json](docs/Abarrato.postman_collection.json)
- Manual de instalación y uso, con datos de prueba y fallas comunes: [docs/MANUAL.md](docs/MANUAL.md)
- Registro de decisiones: [docs/DECISIONES.md](docs/DECISIONES.md)
- Calidad, seguridad y QA (ISO 25010, ISO 27001/27002, OWASP 2025, pruebas de caja blanca, negra y gris): [docs/calidad/](docs/calidad/README.md) · [informe HTML](docs/calidad/informe-calidad.html)
- Base de datos: [db/esquema.sql](db/esquema.sql) (crea la base desde cero) y [db/roles.sql](db/roles.sql) (perfiles de acceso)
- Detalles del backend: [backend/README.md](backend/README.md)

## Instalación rápida

Requisitos: JDK 17 o 21, Maven, Android SDK 35 y un emulador o teléfono Android 7+.

```powershell
# 1. Probar el backend (no necesita base de datos)
cd backend
mvn test

# 2. Compilar la app apuntando al servidor en Google Cloud e instalarla
cd ..\android
.\gradlew.bat :app:installDebug -PabarratoPreciosUrl=https://abarrato-backend-850689460369.southamerica-west1.run.app
```

En VS Code también está la tarea **Compilar APK (servidor en Google Cloud)** (`Ctrl+Shift+B`). Los pasos completos, el emulador y el despliegue están en el [manual](docs/MANUAL.md).

## Pruebas

`mvn test` corre en cada push con GitHub Actions ([.github/workflows/pruebas.yml](.github/workflows/pruebas.yml)):

- `ContratoApiTest`: códigos de estado y formato de error de cada ruta.
- `ExtractorPreciosTest`: lectura de precios y ofertas del HTML de cada tienda.
- `EquivalenciaProductosTest`: qué nombres son el mismo producto y cuáles no.
- `CatalogoEnVivoTest`: búsqueda, respaldo con precios guardados y guardado en segundo plano.
- `SeguridadTest`: límite de peticiones, tamaño máximo y cabeceras.
- App: `RecetasTest` (reconocimiento de ingredientes y orden de las recetas) con `.\gradlew.bat :app:testDebugUnitTest`.
- `CajaNegraTest`, `CajaBlancaTest` y `CajaGrisTest`: las tres técnicas de QA ([guía](docs/calidad/03-QA-pruebas-caja-blanca-negra-gris.md)).

## Seguridad

- **HTTPS siempre**: la app no permite conexiones sin cifrar, y el servidor obliga al navegador a usar HTTPS.
- **Límite de peticiones**: cada IP tiene un cupo por minuto; al pasarse, la API responde 429 y dice cuántos segundos esperar ([docs/API.md](docs/API.md#límite-de-peticiones)).
- **Nunca confiar en el cliente**: el servidor vuelve a validar todo lo que llega (largo de la búsqueda, tamaño del carrito, cuerpo de máximo 64 KB), aunque la app ya lo haya revisado.
- **Errores sin filtrar información**: ningún error muestra trazas, SQL ni nombres internos.
- **Tarea diaria protegida** con un token que solo conoce Cloud Scheduler, comparado en tiempo constante.
- **Cabeceras de seguridad** en todas las respuestas y sin CORS: ninguna página web ajena puede usar la API desde el navegador.

Detalles y limitaciones en [D11](docs/DECISIONES.md).

## Secretos

Ninguna clave está en el repositorio. La base de datos y el token de la tarea diaria se configuran como variables de entorno en Cloud Run (`ABARRATO_DB_URL`, `ABARRATO_DB_USER`, `ABARRATO_DB_PASSWORD`, `ABARRATO_TOKEN_TAREA`). Ver [D8](docs/DECISIONES.md).

## Datos personales

El servidor no tiene cuentas de usuario y no guarda ubicaciones: la lista, la despensa y el historial quedan solo en el teléfono. La ubicación se usa en el teléfono para buscar supermercados cercanos y calcular rutas.

## Uso de inteligencia artificial

Usamos Claude (Anthropic) como asistente para escribir y revisar parte del código del backend y de la app, para depurar errores de compilación y despliegue, y para redactar documentación. Todo el código fue revisado, compilado y probado por el equipo, que es responsable de él.

## Créditos

Mapas © colaboradores de [OpenStreetMap](https://www.openstreetmap.org/copyright) (ODbL), rutas con OSRM. Tarifas de transporte: [Red Movilidad](https://www.red.cl/tarifas-y-recargas/conoce-las-tarifas/). Bencina: [Bencina en Línea](https://www.bencinaenlinea.cl/). Los precios son los publicados en la web de cada supermercado.
