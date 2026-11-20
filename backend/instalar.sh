#!/usr/bin/env bash
# Instala el backend de Abarrato en un servidor Ubuntu 24.04 (Azure, Oracle u otro).
# Uso, desde la carpeta backend:   bash instalar.sh DOMINIO CLAVE_BASE_DE_DATOS
# Ejemplo:  bash instalar.sh abarrato-vale.chilecentral.cloudapp.azure.com Abarrato2026
# Deja: PostgreSQL local, el backend como servicio (se reinicia solo) y HTTPS con Caddy.
set -euo pipefail

DOMINIO="${1:?Falta el dominio. Uso: bash instalar.sh DOMINIO CLAVE}"
CLAVE="${2:?Falta la clave de la base de datos. Uso: bash instalar.sh DOMINIO CLAVE}"
if [[ ! "$CLAVE" =~ ^[A-Za-z0-9]{8,}$ ]]; then
  echo "La clave debe tener al menos 8 letras o números, sin espacios ni símbolos." >&2
  exit 1
fi
CARPETA="$(cd "$(dirname "$0")" && pwd)"

echo "==> 1/6 Memoria de intercambio y zona horaria"
if ! swapon --show | grep -q /swapfile; then
  sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
  sudo mkswap /swapfile >/dev/null && sudo swapon /swapfile
  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab >/dev/null
fi
sudo timedatectl set-timezone America/Santiago || true

echo "==> 2/6 Instalando Java 17, Maven, PostgreSQL y Caddy (tarda unos minutos)"
sudo apt-get update -q
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -q openjdk-17-jdk-headless maven postgresql unzip caddy

echo "==> 3/6 Base de datos"
sudo systemctl enable --now postgresql
if ! sudo -u postgres psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='abarrato'" | grep -q 1; then
  sudo -u postgres psql -qc "CREATE USER abarrato WITH PASSWORD '$CLAVE';"
else
  sudo -u postgres psql -qc "ALTER USER abarrato WITH PASSWORD '$CLAVE';"
fi
if ! sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='abarrato'" | grep -q 1; then
  sudo -u postgres createdb -O abarrato abarrato
fi

echo "==> 4/6 Compilando el backend"
cd "$CARPETA"
mvn -B -q -DskipTests package
sudo mkdir -p /opt/abarrato
sudo cp "$(ls target/*.jar | grep -v original | head -n1)" /opt/abarrato/app.jar
id abarrato >/dev/null 2>&1 || sudo useradd --system --no-create-home --shell /usr/sbin/nologin abarrato

sudo tee /etc/abarrato.env >/dev/null <<ENV
ABARRATO_DB_URL=jdbc:postgresql://localhost:5432/abarrato
ABARRATO_DB_USER=abarrato
ABARRATO_DB_PASSWORD=$CLAVE
SERVER_ADDRESS=127.0.0.1
PORT=8080
JAVA_TOOL_OPTIONS=-Xmx512m -Duser.timezone=America/Santiago
ENV
sudo chmod 600 /etc/abarrato.env

echo "==> 5/6 Servicio del backend"
sudo tee /etc/systemd/system/abarrato.service >/dev/null <<'UNIT'
[Unit]
Description=Backend de precios Abarrato (Spring Boot)
After=network-online.target postgresql.service
Wants=network-online.target

[Service]
User=abarrato
EnvironmentFile=/etc/abarrato.env
ExecStart=/usr/bin/java -jar /opt/abarrato/app.jar
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
UNIT
sudo systemctl daemon-reload
sudo systemctl enable abarrato >/dev/null
sudo systemctl restart abarrato

echo "==> 6/6 HTTPS con Caddy para $DOMINIO"
sudo tee /etc/caddy/Caddyfile >/dev/null <<CADDY
$DOMINIO {
    reverse_proxy 127.0.0.1:8080
}
CADDY
sudo systemctl enable caddy >/dev/null
sudo systemctl restart caddy

echo "Esperando a que el backend inicie..."
for i in $(seq 1 60); do
  if curl -fs -o /dev/null "http://127.0.0.1:8080/api/v1/precios/tiendas"; then
    echo
    echo "Listo. Prueba en el navegador:"
    echo "  https://$DOMINIO/api/v1/precios/en-vivo?q=leche&comercios=JUMBO,SANTA_ISABEL,LIDER,DEL_PEDREGAL"
    exit 0
  fi
  sleep 3
done
echo "El backend no respondió todavía. Revisa con:  sudo journalctl -u abarrato -n 50" >&2
exit 1
