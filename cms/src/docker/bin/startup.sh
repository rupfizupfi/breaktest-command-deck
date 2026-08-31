#!/bin/bash
# Entrypoint for both images (docker/Dockerfile). Two runtime fixups, then exec the app.

# -e so a failed keytool stops the container instead of starting one that cannot serve HTTPS.
set -eu

# Set default password if not provided
KEY_STORE_PASSWORD=${KEY_STORE_PASSWORD:-changeit}

if [ ! -f "/home/appuser/keystore/rupfizupfi.p12" ]; then
  echo "Keystore not found. Creating a new keystore at /home/appuser/keystore/rupfizupfi.p12..."
  keytool -genkeypair -alias rupfizupfi -keyalg RSA -keysize 2048 -storetype PKCS12 \
    -keystore "/home/appuser/keystore/rupfizupfi.p12" -storepass "$KEY_STORE_PASSWORD" \
    -dname "CN=rupfizupfi.ch, OU=IT, O=Rupfizupfi, L=Bern, S=Bern, C=CH"
  echo "Keystore created successfully."
else
  echo "Keystore already exists at /home/appuser/keystore/rupfizupfi.p12."
fi

# The one real secret: the database password, supplied as a compose secret file. One check for one
# requirement - the printed value distinguishes "not set" from "set but unreadable" without a second
# branch to say so.
if [ ! -r "${DB_PASSWORD_FILE:-}" ]; then
  echo "ERROR: DB_PASSWORD_FILE must name a readable file holding the database password; got '${DB_PASSWORD_FILE:-<unset>}'." >&2
  echo "Compose mounts it at /run/secrets/db-password from .secrets/db-password.txt." >&2
  exit 1
fi
export DB_PASSWORD
DB_PASSWORD=$(cat "$DB_PASSWORD_FILE")
echo "Database password loaded from secret file."

# Execute the passed command (typically the Java application)
exec "$@"