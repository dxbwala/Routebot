#!/bin/sh
set -eu

CERT_DIR=/etc/nginx/certs
mkdir -p "${CERT_DIR}"

if [ ! -f "${CERT_DIR}/fullchain.pem" ] || [ ! -f "${CERT_DIR}/privkey.pem" ]; then
  echo "Generating self-signed TLS certificate for nginx..."
  openssl req -x509 -nodes -newkey rsa:2048 -days 825 \
    -keyout "${CERT_DIR}/privkey.pem" \
    -out "${CERT_DIR}/fullchain.pem" \
    -subj "/CN=localhost/O=RouteBot/C=US"
  chmod 600 "${CERT_DIR}/privkey.pem"
fi

nginx -t
exec nginx -g "daemon off;"
