#!/usr/bin/env sh
set -eu

if [ -n "${NGINX_BASIC_AUTH_USER:-}" ] && [ -n "${NGINX_BASIC_AUTH_PASS:-}" ]; then
  if ! command -v openssl >/dev/null 2>&1; then
    echo "openssl not found; cannot generate htpasswd" >&2
    exit 1
  fi
  HASH="$(openssl passwd -apr1 "${NGINX_BASIC_AUTH_PASS}")"
  printf "%s:%s\n" "${NGINX_BASIC_AUTH_USER}" "${HASH}" > /etc/nginx/.htpasswd-monitoring
else
  echo "NGINX_BASIC_AUTH_USER/PASS not set; skipping htpasswd generation" >&2
fi
