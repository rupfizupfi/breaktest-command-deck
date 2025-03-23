#!/bin/bash

# Check if KEY_STORE_PATH is set
if [ -z "$KEY_STORE_PATH" ]; then
  echo "ERROR: KEY_STORE_PATH environment variable is not set"
  exit 1
fi

# Set default password if not provided
KEY_STORE_PASSWORD=${KEY_STORE_PASSWORD:-changeit}

# Create directory for keystore if it doesn't exist
mkdir -p "$(dirname "$KEY_STORE_PATH")"

if [ ! -f "$KEY_STORE_PATH" ]; then
  echo "Keystore not found. Creating a new keystore at $KEY_STORE_PATH..."
  keytool -genkeypair -alias rupfizupfi -keyalg RSA -keysize 2048 -storetype PKCS12 \
    -keystore "$KEY_STORE_PATH" -storepass "$KEY_STORE_PASSWORD" \
    -dname "CN=rupfizupfi.ch, OU=IT, O=Rupfizupfi, L=Bern, S=Bern, C=CH"
  echo "Keystore created successfully."
else
  echo "Keystore already exists at $KEY_STORE_PATH."
fi

if [ -f "/run/secrets/db-password" ]; then
  export DB_PASSWORD=$(cat /run/secrets/db-password)
  echo "Database password loaded from secret file."
else
  echo "ERROR: Database password secret file not found!"
  exit 1
fi

# Execute the passed command (typically the Java application)
exec "$@"