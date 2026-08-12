#!/usr/bin/env bash

set -euo pipefail

: "${POSTGRES_USER:?POSTGRES_USER is required}"
: "${POSTGRES_DB:?POSTGRES_DB is required}"
: "${CONNECT_LAB_POSTGRES_APP_USER:?CONNECT_LAB_POSTGRES_APP_USER is required}"
: "${CONNECT_LAB_POSTGRES_APP_PASSWORD:?CONNECT_LAB_POSTGRES_APP_PASSWORD is required}"

if [[ "$CONNECT_LAB_POSTGRES_APP_USER" != "nexo_connect_lab_app" ]]; then
    printf 'ERROR=POSTGRES_APP_ROLE_IDENTITY_MISMATCH\n' >&2
    exit 2
fi

psql --set=ON_ERROR_STOP=1 \
    --username "$POSTGRES_USER" \
    --dbname "$POSTGRES_DB" \
    --set=app_password="$CONNECT_LAB_POSTGRES_APP_PASSWORD" <<'SQL'
CREATE ROLE nexo_connect_lab_app
    LOGIN
    PASSWORD :'app_password'
    NOSUPERUSER
    NOCREATEDB
    NOCREATEROLE
    NOINHERIT
    NOREPLICATION
    NOBYPASSRLS;
SQL
