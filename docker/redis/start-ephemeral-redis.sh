#!/bin/sh

set -eu

require_hex_secret() {
    value="$1"
    name="$2"
    case "$value" in
        *[!0-9a-f]* | "")
            printf 'ERROR=%s_INVALID\n' "$name" >&2
            exit 2
            ;;
    esac
    if [ "${#value}" -lt 32 ]; then
        printf 'ERROR=%s_TOO_SHORT\n' "$name" >&2
        exit 2
    fi
}

if [ "${CONNECT_LAB_REDIS_APP_USER:-}" != "nexo_connect_lab_app" ]; then
    printf 'ERROR=CONNECT_LAB_REDIS_APP_USER_INVALID\n' >&2
    exit 2
fi

require_hex_secret "${CONNECT_LAB_REDIS_PASSWORD:-}" CONNECT_LAB_REDIS_PASSWORD
require_hex_secret "${CONNECT_LAB_REDIS_APP_PASSWORD:-}" CONNECT_LAB_REDIS_APP_PASSWORD

umask 077
ACL_FILE="$(mktemp /tmp/nexo-connect-lab-redis-acl.XXXXXX)"
trap 'rm -f "$ACL_FILE"' EXIT HUP INT TERM

{
    printf 'user default reset on >%s ~* &* +@all\n' "$CONNECT_LAB_REDIS_PASSWORD"
    printf 'user %s reset on >%s ~nexo-connect-lab:* &nexo.connect.realtime.v1.* +ping +echo +hello +auth +select +client|setname +client|setinfo +publish +subscribe +psubscribe +unsubscribe +punsubscribe +set +get +del +pttl +pexpire +eval +scan +exists\n' \
        "$CONNECT_LAB_REDIS_APP_USER" \
        "$CONNECT_LAB_REDIS_APP_PASSWORD"
} > "$ACL_FILE"

exec redis-server --save "" --appendonly no --aclfile "$ACL_FILE"
