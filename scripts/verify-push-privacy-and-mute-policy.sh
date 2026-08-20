#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
CONTRACT_FILE="${PROJECT_DIR}/docs/architecture/connect-push-privacy-and-mute-policy.properties"

fail() {
    printf 'PUSH_PRIVACY_AND_MUTE_POLICY=FAIL\n' >&2
    printf 'ERROR=%s\n' "$1" >&2
    exit 1
}

property_value() {
    local key="$1"
    awk -F= -v target="$key" '
        $1 == target {
            count++
            value = substr($0, index($0, "=") + 1)
        }
        END {
            if (count != 1) exit 2
            print value
        }
    ' "$CONTRACT_FILE"
}

require_property() {
    local key="$1"
    local expected="$2"
    local actual
    actual="$(property_value "$key")" || fail "PROPERTY_MISSING_OR_DUPLICATE:${key}"
    [[ "$actual" == "$expected" ]] || fail "PROPERTY_VALUE_MISMATCH:${key}"
}

cd "$PROJECT_DIR"

required_files=(
    docs/architecture/CONNECT_24_PUSH_PRIVACY_AND_MUTE_POLICY.md
    docs/architecture/connect-push-privacy-and-mute-policy.properties
    src/main/resources/db/migration/V8__push_privacy_and_mute_policy.sql
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/push/PushNotificationPreference.kt
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/persistence/PushNotificationPreferenceRepository.kt
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/push/PushNotificationPolicy.kt
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresPushNotificationPreferenceRepository.kt
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresDurableTextRepository.kt
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/push/apns/ApnsSandboxNotificationProvider.kt
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/push/apns/ApnsSandboxTransport.kt
    src/test/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/push/PushNotificationPolicyTest.kt
    src/test/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/push/apns/ApnsSandboxNotificationProviderTest.kt
    src/postgresIntegrationTest/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresNotificationOutboxRepositoryIntegrationTest.kt
)

for required_file in "${required_files[@]}"; do
    [[ -f "$required_file" && ! -L "$required_file" ]] || fail "REQUIRED_FILE_MISSING_OR_UNSAFE:${required_file}"
done

require_property contract.version 1
require_property programme NEXO_CONNECT_LAB
require_property phase CONNECT.24
require_property status IMPLEMENTED
require_property preference.truth POSTGRESQL
require_property preference.scope CONVERSATION_AND_REGISTERED_DEVICE
require_property preference.owner AUTHENTICATED_ACTIVE_PARTICIPANT_DEVICE_OWNER
require_property preference.version.fencing REQUIRED
require_property preference.denial NOT_FOUND_OR_DENIED
require_property muted.outbox.intent.count 0
require_property generic.alert.title.loc.key CONNECT_NOTIFICATION_TITLE
require_property generic.alert.body.loc.key CONNECT_NOTIFICATION_NEW_MESSAGE
require_property generic.alert.literal.body false
require_property generic.alert.localization.arguments false
require_property hidden.alert false
require_property badge.set.one 1
require_property badge.unchanged.field false
require_property quiet.mode.hook INJECTED
require_property quiet.mode.presentation BACKGROUND_ONLY
require_property quiet.mode.badge UNCHANGED
require_property outbox.presentation.snapshot REQUIRED
require_property payload.message.body false
require_property payload.sender.identity false
require_property payload.recipient.identity false
require_property payload.device.token false
require_property payload.provider.credential false
require_property apns.alert.push.type ALERT
require_property apns.alert.priority 10
require_property apns.background.push.type BACKGROUND
require_property apns.background.priority 5
require_property nexo.db.direct.access 0
require_property nexo.mutation FORBIDDEN
require_property commit.subject 'feat(connect): [CONNECT.24] enforce push privacy and mute policy'
require_property next.phase CONNECT.25

grep -Fq 'CREATE TABLE connect.push_notification_preferences' \
    src/main/resources/db/migration/V8__push_privacy_and_mute_policy.sql || fail "PREFERENCE_TABLE_MISSING"
grep -Fq 'fk_connect_push_preference_registration_owner' \
    src/main/resources/db/migration/V8__push_privacy_and_mute_policy.sql || fail "REGISTRATION_OWNER_FK_MISSING"
grep -Fq 'presentation_mode TEXT NOT NULL' \
    src/main/resources/db/migration/V8__push_privacy_and_mute_policy.sql || fail "PRESENTATION_SNAPSHOT_COLUMN_MISSING"
grep -Fq 'badge_mode TEXT NOT NULL' \
    src/main/resources/db/migration/V8__push_privacy_and_mute_policy.sql || fail "BADGE_SNAPSHOT_COLUMN_MISSING"
grep -Fq 'NotificationPolicyDecision.SuppressedMuted -> null' \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresDurableTextRepository.kt ||
    fail "MUTE_SUPPRESSION_PATH_MISSING"
grep -Fq 'NotificationQuietModeHook' \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/push/PushNotificationPolicy.kt ||
    fail "QUIET_MODE_HOOK_MISSING"
grep -Fq 'CONNECT_NOTIFICATION_TITLE' \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/push/apns/ApnsSandboxNotificationProvider.kt ||
    fail "GENERIC_TITLE_LOCALISATION_KEY_MISSING"
grep -Fq 'CONNECT_NOTIFICATION_NEW_MESSAGE' \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/push/apns/ApnsSandboxNotificationProvider.kt ||
    fail "GENERIC_BODY_LOCALISATION_KEY_MISSING"
grep -Fq 'ApnsPushType.ALERT' \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/push/apns/ApnsSandboxNotificationProvider.kt ||
    fail "APNS_ALERT_PUSH_TYPE_MISSING"

payload_builder="$(
    sed -n '/private fun privacySafePayload/,/private fun NotificationOutboxIntent.apnsPushType/p' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/push/apns/ApnsSandboxNotificationProvider.kt || true
)"
[[ -n "$payload_builder" ]] || fail "PRIVACY_SAFE_PAYLOAD_BUILDER_MISSING"
if grep -E '(senderSubjectRef|recipientSubjectRef|message\.body|deviceToken|authorization|title-loc-args|loc-args)' \
    <<<"$payload_builder"; then
    fail "FORBIDDEN_PRIVATE_PAYLOAD_FIELD_PRESENT"
fi

grep -Fq 'muted recipient device creates no push intent' \
    src/postgresIntegrationTest/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresNotificationOutboxRepositoryIntegrationTest.kt ||
    fail "MUTED_ZERO_INTENT_PROOF_MISSING"
grep -Fq 'per-device privacy badge and quiet choices are frozen into durable intents' \
    src/postgresIntegrationTest/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresNotificationOutboxRepositoryIntegrationTest.kt ||
    fail "PER_DEVICE_SNAPSHOT_PROOF_MISSING"
grep -Fq 'require(fingerprintCharacter in "0123456789abcdef")' \
    src/postgresIntegrationTest/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresNotificationOutboxRepositoryIntegrationTest.kt ||
    fail "POSTGRES_FINGERPRINT_FIXTURE_GUARD_MISSING"
if grep -E "seedPushDevice\\(.*'[^0-9a-f]'\\)" \
    src/postgresIntegrationTest/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresNotificationOutboxRepositoryIntegrationTest.kt; then
    fail "POSTGRES_FINGERPRINT_FIXTURE_NOT_LOWERCASE_HEXADECIMAL"
fi
grep -Fq 'generic sandbox alert uses fixed localisation keys and redacts private material' \
    src/test/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/push/apns/ApnsSandboxNotificationProviderTest.kt ||
    fail "GENERIC_ALERT_PAYLOAD_PROOF_MISSING"
grep -Fq 'hidden notification is background only and leaves badge unchanged' \
    src/test/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/push/apns/ApnsSandboxNotificationProviderTest.kt ||
    fail "HIDDEN_PAYLOAD_PROOF_MISSING"

./gradlew --no-daemon pushPrivacyPolicyTest --rerun-tasks --console=plain

printf 'PUSH_PRIVACY_AND_MUTE_POLICY=PASS\n'
printf 'MUTED_PUSH_COUNT=0\n'
printf 'FORBIDDEN_PREVIEW_BODY_COUNT=0\n'
printf 'FORBIDDEN_PREVIEW_IDENTITY_COUNT=0\n'
printf 'QUIET_MODE_HOOK=PASS\n'
printf 'APNS_PRIVACY_SAFE_PAYLOAD=PASS\n'
