# Carga precios reales de Jumbo, Santa Isabel, Líder y Del Pedregal en la base de datos de Neon.
# Se ejecuta desde un PC en Chile, porque los supermercados no responden a servidores extranjeros.
#
# Antes, en otra ventana, inicia el backend local conectado a Neon:
#   cd C:\Abarrato\Abarrato\backend
#   $env:ABARRATO_DB_URL="jdbc:postgresql://HOST-DE-NEON/neondb?sslmode=require"
#   $env:ABARRATO_DB_USER="neondb_owner"
#   $env:ABARRATO_DB_PASSWORD="TU_CLAVE"
#   mvn spring-boot:run
# Luego ejecuta este script:  powershell -ExecutionPolicy Bypass -File .\cargar-precios.ps1

$cadenas = "JUMBO,SANTA_ISABEL,LIDER,DEL_PEDREGAL"
$productos = @(
    "leche", "arroz", "aceite", "huevos", "fideos", "azucar", "harina", "pan", "pollo", "carne",
    "atun", "porotos", "lentejas", "cafe", "te", "yogur", "queso", "mantequilla", "margarina",
    "detergente", "papel higienico", "jabon", "shampoo", "tomate", "papas", "cereal", "avena",
    "jugo", "bebida", "agua mineral", "galletas", "salsa de tomate", "mayonesa", "sal", "cloro"
)

$total = 0
foreach ($producto in $productos) {
    $q = [uri]::EscapeDataString($producto)
    Write-Host -NoNewline ("Consultando {0,-18}" -f $producto)
    try {
        $r = Invoke-RestMethod "http://127.0.0.1:8080/api/v1/precios/en-vivo?q=$q&comercios=$cadenas" -TimeoutSec 120
        $ok = ($r.fuentes | Where-Object { $_.estado -eq "RECIENTE" } | ForEach-Object { $_.comercio }) -join ", "
        Write-Host ("{0,4} productos  ({1})" -f $r.productos.Count, $ok)
        $total += $r.productos.Count
    } catch {
        Write-Host "  error: $($_.Exception.Message)"
    }
}
Write-Host ""
Write-Host "Listo: $total productos consultados y guardados en Neon."
