#!/bin/bash
# Entrypoint for both images (docker/Dockerfile): ensure a keystore and its password, load the DB password, exec the app.

# -e so a failed keytool stops the container instead of starting one that cannot serve HTTPS.
set -eu

KEYSTORE_FILE=/home/appuser/keystore/rupfizupfi.p12
# Generated password for a self-signed keystore; lives beside it (same bind mount) so restarts reuse it.
KEYSTORE_PASSWORD_FILE=/home/appuser/keystore/rupfizupfi.p12.password

# Keystore password, first hit wins: env, compose secret, file persisted by an earlier self-sign.
# There is deliberately no built-in default: a well-known password would protect nothing.
KEY_STORE_PASSWORD=${KEY_STORE_PASSWORD:-}
if [ -z "$KEY_STORE_PASSWORD" ] && [ -r /run/secrets/keystore-password ]; then
  KEY_STORE_PASSWORD=$(cat /run/secrets/keystore-password)
fi
if [ -z "$KEY_STORE_PASSWORD" ] && [ -r "$KEYSTORE_PASSWORD_FILE" ]; then
  KEY_STORE_PASSWORD=$(cat "$KEYSTORE_PASSWORD_FILE")
fi

if [ ! -f "$KEYSTORE_FILE" ]; then
  if [ -z "$KEY_STORE_PASSWORD" ]; then
    echo "No keystore password configured; generating a random one and saving it to $KEYSTORE_PASSWORD_FILE (mode 600)."
    KEY_STORE_PASSWORD=$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9')
    (umask 077 && printf '%s' "$KEY_STORE_PASSWORD" > "$KEYSTORE_PASSWORD_FILE")
    chmod 600 "$KEYSTORE_PASSWORD_FILE"
  fi
  echo "Keystore not found. Creating a new keystore at $KEYSTORE_FILE..."
  keytool -genkeypair -alias rupfizupfi -keyalg RSA -keysize 2048 -storetype PKCS12     -keystore "$KEYSTORE_FILE" -storepass "$KEY_STORE_PASSWORD"     -dname "CN=rupfizupfi.ch, OU=IT, O=Rupfizupfi, L=Bern, S=Bern, C=CH"
  echo "Keystore created successfully."
elif [ -z "$KEY_STORE_PASSWORD" ]; then
  echo "ERROR: $KEYSTORE_FILE exists but no keystore password is available." >&2
  echo "Set KEY_STORE_PASSWORD (docker/.env), mount /run/secrets/keystore-password, or put the password in $KEYSTORE_PASSWORD_FILE." >&2
  exit 1
else
  echo "Keystore already exists at $KEYSTORE_FILE."
fi
# Spring reads it as server.ssl.key-store-password=${KEY_STORE_PASSWORD}.
export KEY_STORE_PASSWORD

# The database password arrives as a compose secret file.
if [ ! -r "${DB_PASSWORD_FILE:-}" ]; then
  echo "ERROR: DB_PASSWORD_FILE must name a readable file holding the database password; got '${DB_PASSWORD_FILE:-<unset>}'." >&2
  echo "Compose mounts it at /run/secrets/db-password from .secrets/db-password.txt." >&2
  exit 1
fi
export DB_PASSWORD
DB_PASSWORD=$(cat "$DB_PASSWORD_FILE")
echo "Database password loaded from secret file."

exec "$@"