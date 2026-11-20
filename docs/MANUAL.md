# Manual de instalación y uso de Abarrato

Guía para compilar, probar y usar Abarrato en Windows con VS Code. Al final hay datos de prueba y las fallas más comunes con su solución.

## 1. Instala o comprueba Java y VS Code

1. Instala [Visual Studio Code para Windows](https://code.visualstudio.com/download) si aún no lo tienes.
2. Abre PowerShell y ejecuta:

   ```powershell
   java -version
   javac -version
   $env:JAVA_HOME
   ```

3. Si necesitas Java, descarga **Eclipse Temurin JDK 21**. En Windows x64, abre el instalador MSI y activa las opciones para agregar Java al `PATH` y configurar `JAVA_HOME`. Cierra y vuelve a abrir VS Code y PowerShell. Ejecuta de nuevo las comprobaciones. `JAVA_HOME` debe apuntar a la carpeta raíz del JDK, la que contiene `bin\javac.exe`. [Descarga de Temurin](https://adoptium.net/temurin/releases/?version=21) · [Instalación en Windows](https://adoptium.net/installation/windows)
4. En VS Code, abre **Extensiones** con `Ctrl+Shift+X` e instala **Extension Pack for Java** (Microsoft). **Gradle for Java** es opcional. Ejecuta las tareas de Android desde la terminal integrada de PowerShell. [Extensiones oficiales de Java](https://code.visualstudio.com/docs/java/extensions)

| Componente | Versión del proyecto |
| --- | --- |
| JDK que ejecuta Gradle | 21 recomendado. Android Gradle Plugin requiere al menos 17 |
| Nivel del código fuente Java | 17 |
| Android Gradle Plugin | 8.13.2 |
| Gradle Wrapper | 8.13 |
| SDK de compilación y destino | API 35 |
| Android SDK Build Tools | 35.0.0 |
| Android mínimo para instalar el APK | Android 7.0, API 24 |

Gradle 8.13 admite ejecutarse con JDK 17 o 21. Puedes usar JDK 17 si tus Command-Line Tools también funcionan con él. Si `sdkmanager` indica que necesita una versión posterior de Java, usa JDK 21. El Wrapper del proyecto descarga Gradle 8.13 en la primera compilación. [Compatibilidad de AGP](https://developer.android.com/build/releases/agp-8-13-0-release-notes) · [Compatibilidad de Java con Gradle](https://docs.gradle.org/current/userguide/compatibility.html#java) · [Gradle Wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper_basics.html)

## 2. Prepara el Android SDK

1. En [las descargas de Android](https://developer.android.com/studio#command-line-tools-only), busca **Command line tools only**, elige el ZIP para **Windows** y extráelo.
2. Usa `%LOCALAPPDATA%\Android\Sdk` como raíz del SDK, o conserva la raíz del SDK si ya tienes uno. Dentro crea `cmdline-tools\latest`. Copia **el contenido** de la carpeta `cmdline-tools` extraída dentro de `latest`. Confirma que la estructura queda así:

   ```text
   Sdk/
     cmdline-tools/
       latest/
         bin/
           sdkmanager.bat
           avdmanager.bat
         lib/
         NOTICE.txt
         source.properties
   ```

3. En Windows, busca **Editar las variables de entorno de tu cuenta**. Crea la variable de usuario `ANDROID_HOME` con la ruta absoluta de la raíz del SDK. Puedes obtener la ruta habitual ejecutando `Join-Path $env:LOCALAPPDATA 'Android\Sdk'`. En el `Path` de usuario, agrega las rutas absolutas de `cmdline-tools\latest\bin` y `platform-tools` bajo esa raíz. Reinicia VS Code. [Variables de Android](https://developer.android.com/tools/variables)
4. Abre **Terminal → Nuevo terminal** en VS Code. Instala la plataforma, las herramientas de compilación y `adb`. Lee y acepta las licencias que correspondan cuando se te solicite.

   ```powershell
   $sdkmanager = Join-Path $env:ANDROID_HOME 'cmdline-tools\latest\bin\sdkmanager.bat'
   & $sdkmanager "--sdk_root=$env:ANDROID_HOME" --version
   & $sdkmanager "--sdk_root=$env:ANDROID_HOME" --licenses
   & $sdkmanager "--sdk_root=$env:ANDROID_HOME" "platform-tools" "platforms;android-35" "build-tools;35.0.0"

   Test-Path (Join-Path $env:ANDROID_HOME 'platforms\android-35\android.jar')
   adb version
   ```

   `Test-Path` debe devolver `True`. [Referencia de sdkmanager](https://developer.android.com/tools/sdkmanager)

## 3. Prueba el backend

Desde la carpeta `backend`:

```powershell
mvn test
```

Debe terminar en `BUILD SUCCESS`. Las pruebas no usan base de datos ni internet.

Para levantar el servidor en tu PC necesitas una base PostgreSQL (puede ser la de Neon). Define las variables en la misma terminal **sin guardarlas en ningún archivo del proyecto**:

```powershell
$env:ABARRATO_DB_URL = 'jdbc:postgresql://<host-de-neon>/neondb?sslmode=require'
$env:ABARRATO_DB_USER = 'neondb_owner'
$env:ABARRATO_DB_PASSWORD = '<la clave, desde el panel de Neon>'
$env:ABARRATO_SCRAPE_ENABLED = 'false'
mvn spring-boot:run
```

Cuando aparezca `Started AbarratoApplication`, el servidor está listo (la terminal queda "congelada" a propósito: el servidor está corriendo). Pruébalo desde otra terminal:

```powershell
Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/precios/tiendas'
```

## 4. Despliega el backend en Google Cloud Run

Desde `backend`, con `gcloud` iniciado en el proyecto:

```powershell
gcloud run deploy abarrato-backend --source . --region southamerica-west1 --allow-unauthenticated --timeout 3600 --memory 1Gi --cpu-boost --min-instances 0 --max-instances 2
```

Las variables (`ABARRATO_DB_URL`, `ABARRATO_DB_USER`, `ABARRATO_DB_PASSWORD`, `ABARRATO_SCRAPE_ENABLED=false`, `ABARRATO_TOKEN_TAREA`) se configuran una sola vez en la consola de Cloud Run → **Editar y desplegar nueva revisión → Variables y secretos**, y se conservan entre despliegues. La tarea diaria la ejecuta Cloud Scheduler (`abarrato-actualizar-precios`, 06:00 hora de Chile).

## 5. Compila e instala la app

Abre el emulador (en este equipo el dispositivo virtual se llama `Pixel_10_Pro`):

```powershell
& "$env:ANDROID_HOME\emulator\emulator.exe" -avd Pixel_10_Pro
```

Espera a que Android muestre la pantalla de inicio. En otra terminal, desde `android`:

```powershell
adb devices                     # debe decir "device", no "offline"
.\gradlew.bat :app:installDebug -PabarratoPreciosUrl=https://abarrato-backend-850689460369.southamerica-west1.run.app
adb shell am start -n com.abarrato.app/.MainActivity
```

Sin `-PabarratoPreciosUrl`, la versión de depuración usa `http://10.0.2.2:8080`, que desde el emulador es el servidor de tu PC (paso 3).

## 6. Recorre la app

- **Inicio**: gasto de la semana y, debajo, cuánto ahorraste; última compra; avance de la despensa.
- **Compra**: escribe un producto (mínimo 2 letras). Aparecen primero los precios guardados y el aviso "Actualizando…"; segundos después llegan los precios en vivo. Cada resultado muestra el precio más bajo considerando ofertas, y etiquetas "↓ Bajó $X desde ayer" o "% Oferta". Al tocar un producto se ven los precios por tienda; toca una fila para elegir esa tienda.
- **Despensa**: lo que tienes en casa; al optimizar solo se compra lo que falta.
- **Perfil**: cómo te mueves (a pie, auto con bencina 93/95/97, metro o micro) y qué priorizas.
- **⚡ Optimizar**: mapa de supermercados a 2 km, mejor plan y su desglose. La confirmación muestra por separado **Ahorras en el supermercado** y **Gastas en transporte**.

## 7. Datos de prueba

| Qué | Valor |
|---|---|
| Ubicación en el emulador (Controles extendidos → Location) | Providencia: latitud `-33.429`, longitud `-70.617` |
| Búsquedas con resultados en varias tiendas | `leche entera`, `arroz grado 1`, `aceite maravilla`, `7up`, `huevos` |
| Búsqueda inválida (para ver el mensaje de error) | `a` → "La búsqueda debe tener entre 2 y 80 caracteres" |
| Probar la API sin la app | colección [Abarrato.postman_collection.json](Abarrato.postman_collection.json) |

## 8. Fallas comunes

| Síntoma | Causa | Solución |
|---|---|---|
| `No connected devices` o `adb devices` dice `offline` | El emulador no terminó de arrancar | Espera la pantalla de inicio; si sigue, en el Device Manager haz **Cold Boot** |
| `The system cannot find the path` al ejecutar `gradlew.bat` | La terminal no está en la carpeta `android` | `cd C:\AbarratoNuevo\Abarrato\android` |
| `mvn` dice `there is no POM in this directory` | La terminal no está en `backend` | `cd ..\backend` |
| `SDK location not found` | Falta `ANDROID_HOME` | Paso 2, o crea `android/local.properties` con `sdk.dir=...` |
| El servidor local se queda en `Started ...` | No es un error: está esperando peticiones | Usa otra terminal para probar |
| La app dice "No se pudo conectar con el servidor de precios" | APK compilado sin la URL de Cloud Run, o sin internet | Reinstala con `-PabarratoPreciosUrl=...` (paso 5) |
| 404 al abrir la URL del servidor | Se copió una URL de ejemplo | Usa la URL real del paso 5 y agrega `/api/v1/precios/tiendas` |
| Una tienda sale como `ULTIMA_CAPTURA` | Esa web no respondió a tiempo | Normal: se muestran los últimos precios guardados de esa tienda |
| Muchos errores de compilación en una clase que nadie tocó | Archivo de otra versión del proyecto mezclado | Borra el archivo sobrante y vuelve a compilar |
