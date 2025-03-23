#!/bin/bash

# Set default password if not provided
KEY_STORE_PASSWORD=${KEY_STORE_PASSWORD:-changeit}

# Create directory for keystore if it doesn't exist
mkdir -p "/home/appuser/rupfizupfi"

if [ ! -f "/home/appuser/keystore/rupfizupfi.p12" ]; then
  echo "Keystore not found. Creating a new keystore at /home/appuser/keystore/rupfizupfi.p12..."
  keytool -genkeypair -alias rupfizupfi -keyalg RSA -keysize 2048 -storetype PKCS12 \
    -keystore "/home/appuser/keystore/rupfizupfi.p12" -storepass "$KEY_STORE_PASSWORD" \
    -dname "CN=rupfizupfi.ch, OU=IT, O=Rupfizupfi, L=Bern, S=Bern, C=CH"
  echo "Keystore created successfully."
else
  echo "Keystore already exists at /home/appuser/keystore/rupfizupfi.p12."
fi

# Check if DB_PASSWORD_FILE is set
if [ -z "$DB_PASSWORD_FILE" ]; then
  echo "ERROR: DB_PASSWORD_FILE environment variable is not set"
  exit 1
fi

# Read DB password from secret file
if [ -f "$DB_PASSWORD_FILE" ]; then
  export DB_PASSWORD=$(cat "$DB_PASSWORD_FILE")
  echo "Database password loaded from secret file."
else
  echo "ERROR: Database password secret file not found at $DB_PASSWORD_FILE!"
  exit 1
fi

# Execute the passed command (typically the Java application)
exec "$@"