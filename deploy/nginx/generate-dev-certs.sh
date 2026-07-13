#!/usr/bin/env bash
# Generate a local self-signed TLS cert for nginx (dev only).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CERT_DIR="${ROOT}/deploy/nginx/certs"
mkdir -p "${CERT_DIR}"

if [[ -f "${CERT_DIR}/fullchain.pem" && -f "${CERT_DIR}/privkey.pem" ]]; then
  echo "Certs already exist in ${CERT_DIR}"
  exit 0
fi

openssl req -x509 -nodes -newkey rsa:2048 -days 825 \
  -keyout "${CERT_DIR}/privkey.pem" \
  -out "${CERT_DIR}/fullchain.pem" \
  -subj "/CN=localhost/O=RouteBot/C=US" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"

chmod 600 "${CERT_DIR}/privkey.pem"
echo "Wrote ${CERT_DIR}/fullchain.pem and privkey.pem"
