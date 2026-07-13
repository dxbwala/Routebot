#!/bin/sh
set -eu

CERT_DIR=/etc/nginx/certs
CERT="${CERT_DIR}/fullchain.pem"
KEY="${CERT_DIR}/privkey.pem"

if [ -f "${CERT}" ] && [ -f "${KEY}" ]; then
  echo "Using mounted TLS certificate: ${CERT}"
else
  echo "No certificate found at ${CERT_DIR}; generating a self-signed dev certificate..."
  mkdir -p "${CERT_DIR}"
  openssl req -x509 -nodes -newkey rsa:2048 -days 825 \
    -keyout "${KEY}" \
    -out "${CERT}" \
    -subj "/CN=localhost/O=RouteBot/C=US"
  chmod 600 "${KEY}"
fi

nginx -t
exec nginx -g "daemon off;"
