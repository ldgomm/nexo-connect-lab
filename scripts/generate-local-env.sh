#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
OUTPUT_PATH="${1:-${PROJECT_DIR}/.env}"

if [[ "$OUTPUT_PATH" != /* ]]; then
    OUTPUT_PATH="${PROJECT_DIR}/${OUTPUT_PATH}"
fi

if [[ -e "$OUTPUT_PATH" || -L "$OUTPUT_PATH" ]]; then
    printf 'ERROR=ENV_FILE_ALREADY_EXISTS\n' >&2
    exit 3
fi

for template in "${PROJECT_DIR}/.env.example" "${PROJECT_DIR}/.env.host.example"; do
    if [[ ! -f "$template" ]]; then
        printf 'ERROR=ENV_TEMPLATE_MISSING\n' >&2
        exit 4
    fi
done

if ! command -v openssl >/dev/null 2>&1; then
    printf 'ERROR=OPENSSL_NOT_AVAILABLE\n' >&2
    exit 5
fi

mkdir -p "$(dirname -- "$OUTPUT_PATH")"
umask 077
TEMP_PATH="$(mktemp "${OUTPUT_PATH}.tmp.XXXXXX")"

cleanup() {
    /bin/rm -f "$TEMP_PATH"
}
trap cleanup EXIT

POSTGRES_PASSWORD="$(openssl rand -hex 32)"
POSTGRES_APP_PASSWORD="$(openssl rand -hex 32)"
REDIS_PASSWORD="$(openssl rand -hex 32)"
REDIS_APP_PASSWORD="$(openssl rand -hex 32)"
MINIO_ROOT_PASSWORD="$(openssl rand -hex 32)"
SYNTHETIC_BUSINESS_TOKEN="$(openssl rand -hex 32)"
SYNTHETIC_CLIENT_TOKEN="$(openssl rand -hex 32)"
PUSH_TOKEN_ENCRYPTION_KEY_B64="$(openssl rand -base64 32)"
PUSH_TOKEN_FINGERPRINT_KEY_B64="$(openssl rand -base64 32)"

awk \
    -v postgres_password="$POSTGRES_PASSWORD" \
    -v postgres_app_password="$POSTGRES_APP_PASSWORD" \
    -v redis_password="$REDIS_PASSWORD" \
    -v redis_app_password="$REDIS_APP_PASSWORD" \
    -v minio_root_password="$MINIO_ROOT_PASSWORD" \
    -v synthetic_business_token="$SYNTHETIC_BUSINESS_TOKEN" \
    -v synthetic_client_token="$SYNTHETIC_CLIENT_TOKEN" \
    -v push_token_encryption_key_b64="$PUSH_TOKEN_ENCRYPTION_KEY_B64" \
    -v push_token_fingerprint_key_b64="$PUSH_TOKEN_FINGERPRINT_KEY_B64" '
    {
        gsub(/__CONNECT_LAB_POSTGRES_PASSWORD__/, postgres_password)
        gsub(/__CONNECT_LAB_POSTGRES_APP_PASSWORD__/, postgres_app_password)
        gsub(/__CONNECT_LAB_REDIS_PASSWORD__/, redis_password)
        gsub(/__CONNECT_LAB_REDIS_APP_PASSWORD__/, redis_app_password)
        gsub(/__CONNECT_LAB_MINIO_ROOT_PASSWORD__/, minio_root_password)
        gsub(/__CONNECT_LAB_SYNTHETIC_BUSINESS_TOKEN__/, synthetic_business_token)
        gsub(/__CONNECT_LAB_SYNTHETIC_CLIENT_TOKEN__/, synthetic_client_token)
        gsub(/__CONNECT_LAB_PUSH_TOKEN_ENCRYPTION_KEY_B64__/, push_token_encryption_key_b64)
        gsub(/__CONNECT_LAB_PUSH_TOKEN_FINGERPRINT_KEY_B64__/, push_token_fingerprint_key_b64)
        print
    }
' "${PROJECT_DIR}/.env.example" "${PROJECT_DIR}/.env.host.example" >"$TEMP_PATH"

if grep -Eq '__CONNECT_LAB_[A-Z0-9_]+__' "$TEMP_PATH"; then
    printf 'ERROR=UNRESOLVED_ENV_PLACEHOLDER\n' >&2
    exit 6
fi

chmod 600 "$TEMP_PATH"
if ! ln "$TEMP_PATH" "$OUTPUT_PATH" 2>/dev/null; then
    printf 'ERROR=ENV_FILE_ALREADY_EXISTS\n' >&2
    exit 3
fi

/bin/rm -f "$TEMP_PATH"
trap - EXIT
printf 'ENV_FILE_CREATED=%s\n' "$OUTPUT_PATH"
