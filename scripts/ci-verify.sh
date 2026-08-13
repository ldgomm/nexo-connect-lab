#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
ENV_FILE="${PROJECT_DIR}/.env"
COMPOSE_FILE="${PROJECT_DIR}/compose.yaml"
COMPOSE_PROJECT="nexo-connect-lab"

ENV_CREATED=0
ENV_BEFORE_SHA=""
STACK_MAY_EXIST=0
CLEANUP_RESULT="NOT_REQUIRED"
CONTAINERS_LEFT="NOT_CHECKED"
VOLUMES_LEFT="NOT_CHECKED"
NETWORKS_LEFT="NOT_CHECKED"

compose() {
    docker compose --env-file "$ENV_FILE" --file "$COMPOSE_FILE" "$@"
}

resource_count() {
    local resource_type="$1"
    case "$resource_type" in
        container)
            docker ps -aq --filter "label=com.docker.compose.project=${COMPOSE_PROJECT}" 2>/dev/null |
                awk 'NF { count++ } END { print count + 0 }'
            ;;
        volume)
            docker volume ls -q --filter "label=com.docker.compose.project=${COMPOSE_PROJECT}" 2>/dev/null |
                awk 'NF { count++ } END { print count + 0 }'
            ;;
        network)
            docker network ls -q --filter "label=com.docker.compose.project=${COMPOSE_PROJECT}" 2>/dev/null |
                awk 'NF { count++ } END { print count + 0 }'
            ;;
        *)
            return 2
            ;;
    esac
}

finish() {
    local status=$?
    trap - EXIT
    set +e

    if [[ "$status" -ne 0 && "$STACK_MAY_EXIST" -eq 1 ]]; then
        compose ps --all >&2 || true
        compose logs --no-color --timestamps app >&2 || true
    fi

    if [[ "$STACK_MAY_EXIST" -eq 1 ]]; then
        if compose --profile setup --profile migration down --volumes --remove-orphans; then
            CLEANUP_RESULT="PASS"
        else
            CLEANUP_RESULT="FAIL"
            status=1
        fi
    fi

    if command -v docker >/dev/null 2>&1; then
        CONTAINERS_LEFT="$(resource_count container)"
        VOLUMES_LEFT="$(resource_count volume)"
        NETWORKS_LEFT="$(resource_count network)"
        if [[ "$CONTAINERS_LEFT" != "0" || "$VOLUMES_LEFT" != "0" || "$NETWORKS_LEFT" != "0" ]]; then
            CLEANUP_RESULT="FAIL"
            status=1
        elif [[ "$STACK_MAY_EXIST" -eq 1 && "$CLEANUP_RESULT" != "FAIL" ]]; then
            CLEANUP_RESULT="PASS"
        fi
    fi

    if [[ "$ENV_CREATED" -eq 1 ]]; then
        /bin/rm -f "$ENV_FILE"
    elif [[ -n "$ENV_BEFORE_SHA" && -f "$ENV_FILE" ]]; then
        if [[ "$(shasum -a 256 "$ENV_FILE" | awk '{print $1}')" != "$ENV_BEFORE_SHA" ]]; then
            printf 'LOCAL_ENV_PRESERVATION=FAIL\n' >&2
            status=1
        fi
    fi

    printf 'STACK_CLEANUP=%s\n' "$CLEANUP_RESULT"
    printf 'CONTAINERS_LEFT=%s\n' "$CONTAINERS_LEFT"
    printf 'VOLUMES_LEFT=%s\n' "$VOLUMES_LEFT"
    printf 'NETWORKS_LEFT=%s\n' "$NETWORKS_LEFT"

    if [[ "$status" -eq 0 ]]; then
        printf 'CI_RESULT=PASS\n'
    else
        printf 'CI_RESULT=FAIL\n' >&2
    fi
    exit "$status"
}

trap finish EXIT

cd "$PROJECT_DIR"

required_files=(
    .editorconfig
    .dockerignore
    .env.example
    .env.host.example
    .github/workflows/ci.yml
    .gitignore
    Dockerfile
    Makefile
    build.gradle.kts
    compose.yaml
    docker/postgres/init/001-create-connect-app-role.sh
    docs/governance/CONNECT_PHASE_GOVERNANCE.md
    docs/governance/INTELLIJ_FORMATTING.md
    docs/governance/SEMANTIC_ACCEPTANCE_GATES.md
    docs/governance/connect-ownership.properties
    docs/governance/connect-phase-ledger.tsv
    docs/governance/connect-phase-policy.properties
    gradle/libs.versions.toml
    gradlew
    scripts/ci-verify.sh
    scripts/generate-local-env.sh
    scripts/smoke-local-stack.sh
    scripts/verify-authenticated-websocket.sh
    scripts/verify-authorized-conversation-subscriptions.sh
    scripts/verify-database-lifecycle.sh
    scripts/verify-durable-message-created-events.sh
    scripts/verify-durable-catch-up-resync.sh
    scripts/verify-durable-receipts.sh
    scripts/verify-realtime-transport-hardening.sh
    scripts/verify-semantic-acceptance.sh
    scripts/verify-durable-restart-recovery.sh
    scripts/verify-postgres-repository.sh
    scripts/verify-postgres-schema.sh
    scripts/verify-phase-governance.sh
    scripts/verify-formatting-convergence.sh
    settings.gradle.kts
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/persistence/ConversationRepository.kt
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/persistence/DurableMessageHistoryRepository.kt
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/persistence/DurableReceiptCursorRepository.kt
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/realtime/ConversationSubscriptionAuthorizer.kt
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/realtime/AuthorizedConversationEventHub.kt
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/realtime/DurableTextMessageCoordinator.kt
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/realtime/DurableConversationCatchUp.kt
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/realtime/DurableReceiptCursorService.kt
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/realtime/RealtimeTransport.kt
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/realtime/RealtimeTransportHardening.kt
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/routes/AuthenticatedRealtimeRoutes.kt
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/routes/DurableTextMessageRoutes.kt
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/realtime/RealtimeProtocol.kt
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/realtime/DurableMessageCreatedEvent.kt
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/realtime/DurableReceiptCursor.kt
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresDurableReceiptCursorRepository.kt
    src/main/resources/db/migration/V5__durable_receipt_cursors.sql
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/conversation/DurableConversationListing.kt
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/conversation/CreateBusinessClientConversationCommand.kt
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/conversation/DurableConversationSnapshot.kt
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/persistence/BusinessClientConversationKeyPersistenceRecord.kt
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/persistence/BusinessClientConversationPersistenceBundle.kt
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/message/DurableMessageHistory.kt
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresConversationRepository.kt
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresDurableMessageHistoryRepository.kt
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresDatabaseLifecycle.kt
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/identity/SyntheticRealtimeIdentityRegistry.kt
    src/main/resources/db/migration/V2__connect_application_role_grants.sql
    src/main/resources/db/migration/V3__business_client_conversation_keys.sql
    src/main/resources/db/migration/V4__durable_conversation_activity_listing.sql
    src/postgresIntegrationTest/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresConversationRepositoryIntegrationTest.kt
    src/postgresIntegrationTest/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresConversationListingIntegrationTest.kt
    src/postgresIntegrationTest/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresDurableMessageHistoryRepositoryIntegrationTest.kt
    src/postgresIntegrationTest/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresDurableRestartRecoveryIntegrationTest.kt
    src/test/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/persistence/BusinessClientConversationPersistenceBundleTest.kt
    src/test/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/conversation/DurableConversationListingTest.kt
    src/test/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/message/DurableMessageHistoryTest.kt
    src/test/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/routes/AuthenticatedRealtimeRoutesTest.kt
    src/test/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/routes/AuthenticatedRealtimeRuntimeTest.kt
    src/test/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/realtime/RealtimeProtocolTest.kt
    src/test/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/realtime/ConversationSubscriptionAuthorizerTest.kt
    src/test/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/realtime/AuthorizedConversationEventHubTest.kt
    src/test/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/realtime/DurableTextMessageCoordinatorTest.kt
    src/test/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/realtime/DurableMessageCreatedEventTest.kt
    src/test/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/realtime/DurableConversationCatchUpTest.kt
    src/test/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/routes/AuthenticatedRealtimeCatchUpRuntimeTest.kt
    src/test/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/routes/AuthenticatedRealtimeReceiptRuntimeTest.kt
    src/test/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/realtime/RealtimeTransportHardeningTest.kt
    src/test/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/realtime/DurableReceiptCursorTest.kt
    src/test/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/identity/SyntheticRealtimeIdentityRegistryTest.kt
    src/test/kotlin/com/premierdarkcoffee/nexo/connect/lab/acceptance/SemanticAcceptanceGateTest.kt
)

for required_file in "${required_files[@]}"; do
    if [[ ! -f "$required_file" ]]; then
        printf 'CI_STATIC_CONTRACT=FAIL\n' >&2
        printf 'ERROR=REQUIRED_FILE_MISSING:%s\n' "$required_file" >&2
        exit 10
    fi
done

if [[ "$(git rev-parse --show-toplevel 2>/dev/null || true)" != "$PROJECT_DIR" ]]; then
    printf 'CI_STATIC_CONTRACT=FAIL\n' >&2
    printf 'ERROR=REPOSITORY_IDENTITY_MISMATCH\n' >&2
    exit 11
fi

if ! git check-ignore -q .env || git ls-files --error-unmatch .env >/dev/null 2>&1; then
    printf 'CI_STATIC_CONTRACT=FAIL\n' >&2
    printf 'ERROR=LOCAL_ENV_TRACKING_CONTRACT_MISMATCH\n' >&2
    exit 12
fi

if git grep -n -E \
    '(mongodb\+srv://|postgres(ql)?://[^[:space:]/:@]+:[^[:space:]@]+@|redis://|localhost:8080|@nexo\.test|NexoSuper|NexoStaging|BEGIN [A-Z ]*PRIVATE KEY|sk-[A-Za-z0-9_-]{16,})' \
    -- . ':!.github/workflows/ci.yml' ':!scripts/ci-verify.sh'; then
    printf 'CI_STATIC_CONTRACT=FAIL\n' >&2
    printf 'ERROR=FORBIDDEN_SECRET_OR_NEXO_ENDPOINT_PRESENT\n' >&2
    exit 13
fi

if grep -Eq 'pull_request_target:|permissions:[[:space:]]*(write-all|write)|contents:[[:space:]]*write|secrets\.' \
    .github/workflows/ci.yml; then
    printf 'CI_STATIC_CONTRACT=FAIL\n' >&2
    printf 'ERROR=UNSAFE_WORKFLOW_PRIVILEGE_OR_SECRET_REFERENCE\n' >&2
    exit 14
fi

if ! grep -Fqx '    runs-on: ubuntu-24.04' .github/workflows/ci.yml || \
    ! grep -Fqx '        uses: actions/checkout@v4' .github/workflows/ci.yml || \
    ! grep -Fqx '          fetch-depth: 0' .github/workflows/ci.yml || \
    ! grep -Fqx '          persist-credentials: false' .github/workflows/ci.yml || \
    ! grep -Fqx '        uses: actions/setup-java@v4' .github/workflows/ci.yml || \
    ! grep -Fqx '          distribution: temurin' .github/workflows/ci.yml || \
    ! grep -Fqx '          java-version: "21"' .github/workflows/ci.yml || \
    ! grep -Fqx '        run: ./scripts/ci-verify.sh' .github/workflows/ci.yml; then
    printf 'CI_STATIC_CONTRACT=FAIL\n' >&2
    printf 'ERROR=WORKFLOW_EXECUTION_CONTRACT_MISMATCH\n' >&2
    exit 15
fi

if grep -Eq 'docker (push|login)|kubectl|helm|deploy|environment:' .github/workflows/ci.yml; then
    printf 'CI_STATIC_CONTRACT=FAIL\n' >&2
    printf 'ERROR=DEPLOYMENT_OR_PUBLICATION_FORBIDDEN_IN_CONNECT_ZERO\n' >&2
    exit 16
fi

if [[ "$(grep -c '^      - \"127\.0\.0\.1:' compose.yaml)" != "5" ]] || \
    grep -Eq '^[[:space:]]+(container_name:|internal:[[:space:]]*true|external:[[:space:]]*true)|^[[:space:]]+image:[[:space:]]*[^[:space:]]+:latest([[:space:]]|$)' compose.yaml; then
    printf 'CI_STATIC_CONTRACT=FAIL\n' >&2
    printf 'ERROR=COMPOSE_ISOLATION_CONTRACT_MISMATCH\n' >&2
    exit 17
fi

app_block="$(awk '/^  app:$/,/^  postgres:$/' compose.yaml)"
if grep -Eq 'CONNECT_LAB_POSTGRES_(USER|PASSWORD):|REDIS_PASSWORD|MINIO_ROOT_(USER|PASSWORD)' <<<"$app_block" || \
    ! grep -Fq 'CONNECT_LAB_POSTGRES_APP_USER:' <<<"$app_block" || \
    ! grep -Fq 'CONNECT_LAB_POSTGRES_APP_PASSWORD:' <<<"$app_block"; then
    printf 'CI_STATIC_CONTRACT=FAIL\n' >&2
    printf 'ERROR=APPLICATION_RECEIVES_INFRASTRUCTURE_ROOT_SECRET\n' >&2
    exit 18
fi

for disabled_flag in \
    CONNECT_LAB_NEXO_INTEGRATION_ENABLED \
    CONNECT_LAB_NEXO_DB_DIRECT_ACCESS \
    CONNECT_LAB_CALLS_ENABLED \
    CONNECT_LAB_E2EE_CLAIM; do
    if ! grep -Fqx "${disabled_flag}=false" .env.example; then
        printf 'CI_STATIC_CONTRACT=FAIL\n' >&2
        printf 'ERROR=FORBIDDEN_CONNECT_ZERO_FLAG_NOT_DISABLED:%s\n' "$disabled_flag" >&2
        exit 19
    fi
done

bash -n scripts/generate-local-env.sh
bash -n scripts/smoke-local-stack.sh
bash -n scripts/verify-authenticated-websocket.sh
bash -n scripts/verify-authorized-conversation-subscriptions.sh
bash -n scripts/verify-database-lifecycle.sh
bash -n scripts/verify-durable-message-created-events.sh
bash -n scripts/verify-durable-catch-up-resync.sh
bash -n scripts/verify-durable-receipts.sh
bash -n scripts/verify-realtime-transport-hardening.sh
bash -n scripts/verify-semantic-acceptance.sh
bash -n scripts/verify-durable-restart-recovery.sh
bash -n scripts/verify-postgres-repository.sh
bash -n scripts/verify-postgres-schema.sh
bash -n scripts/verify-phase-governance.sh
bash -n scripts/verify-formatting-convergence.sh
bash -n docker/postgres/init/001-create-connect-app-role.sh
bash -n scripts/ci-verify.sh
git diff --check

./scripts/verify-phase-governance.sh
printf 'PHASE_GOVERNANCE_CONTRACT=PASS\n'

./scripts/verify-formatting-convergence.sh
printf 'FORMATTER_CONVERGENCE_CONTRACT=PASS\n'

./scripts/verify-semantic-acceptance.sh
printf 'SEMANTIC_ACCEPTANCE_CONTRACT=PASS\n'

CONNECT_C5_MIGRATION_DIRECTORY="src/main/resources/db/migration"
CONNECT_C5_MIGRATION_FILE_COUNT="$(
    find "$CONNECT_C5_MIGRATION_DIRECTORY" -maxdepth 1 -type f -name 'V*__*.sql' -print |
        wc -l |
        tr -d '[:space:]'
)"
CONNECT_C5_V1_COUNT="$(find "$CONNECT_C5_MIGRATION_DIRECTORY" -maxdepth 1 -type f -name 'V1__*.sql' -print | wc -l | tr -d '[:space:]')"
CONNECT_C5_V2_COUNT="$(find "$CONNECT_C5_MIGRATION_DIRECTORY" -maxdepth 1 -type f -name 'V2__*.sql' -print | wc -l | tr -d '[:space:]')"
CONNECT_C5_V3_COUNT="$(find "$CONNECT_C5_MIGRATION_DIRECTORY" -maxdepth 1 -type f -name 'V3__*.sql' -print | wc -l | tr -d '[:space:]')"
CONNECT_C5_V4_COUNT="$(find "$CONNECT_C5_MIGRATION_DIRECTORY" -maxdepth 1 -type f -name 'V4__*.sql' -print | wc -l | tr -d '[:space:]')"
CONNECT_C5_V5_COUNT="$(find "$CONNECT_C5_MIGRATION_DIRECTORY" -maxdepth 1 -type f -name 'V5__*.sql' -print | wc -l | tr -d '[:space:]')"
if [[ "$CONNECT_C5_MIGRATION_FILE_COUNT" != "5" ]] ||
    [[ "$CONNECT_C5_V1_COUNT" != "1" ]] ||
    [[ "$CONNECT_C5_V2_COUNT" != "1" ]] ||
    [[ "$CONNECT_C5_V3_COUNT" != "1" ]] ||
    [[ "$CONNECT_C5_V4_COUNT" != "1" ]] ||
    [[ "$CONNECT_C5_V5_COUNT" != "1" ]] ||
    [[ ! -f "$CONNECT_C5_MIGRATION_DIRECTORY/V5__durable_receipt_cursors.sql" ]]; then
    printf 'CI_STATIC_CONTRACT=FAIL\n' >&2
    printf 'ERROR=CONNECT_C5_MIGRATION_SET_MISMATCH\n' >&2
    exit 20
fi
CONNECT_C5_MIGRATION_SET=EXACT_V1_TO_V5

if grep -En 'route\(|webSocket|WebSocket|/messages|/conversations' \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/persistence/ConversationRepository.kt \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/conversation/DurableConversationListing.kt \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresConversationRepository.kt; then
    printf 'CI_STATIC_CONTRACT=FAIL\n' >&2
    printf 'ERROR=CONNECT_B5_TRANSPORT_SCOPE_VIOLATION\n' >&2
    exit 20
fi

if grep -En 'OFFSET|SELECT[[:space:]].*body|message\.body' \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresConversationRepository.kt ||
    ! grep -Fq 'last_activity_at = GREATEST(last_activity_at, ?)' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresDurableTextRepository.kt ||
    ! grep -Fq 'conversation.conversation_ref COLLATE "C" < ? COLLATE "C"' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresConversationRepository.kt ||
    ! grep -Fq 'request.pageSize + 1' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresConversationRepository.kt ||
    ! grep -Fq 'PRINCIPAL_TYPE_NOT_SUPPORTED' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/persistence/ConversationRepository.kt; then
    printf 'CI_STATIC_CONTRACT=FAIL\n' >&2
    printf 'ERROR=CONNECT_B5_LISTING_CONTRACT_MISMATCH\n' >&2
    exit 20
fi

if grep -En 'route\(|webSocket|WebSocket|/messages|/conversations' \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/persistence/DurableMessageHistoryRepository.kt \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/message/DurableMessageHistory.kt \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresDurableMessageHistoryRepository.kt; then
    printf 'CI_STATIC_CONTRACT=FAIL\n' >&2
    printf 'ERROR=CONNECT_B6_TRANSPORT_SCOPE_VIOLATION\n' >&2
    exit 20
fi

if grep -En 'OFFSET|message_identities|client_message_ref|idempotency_key|payload_fingerprint|INSERT[[:space:]]|UPDATE[[:space:]]|DELETE[[:space:]]|TRUNCATE[[:space:]]' \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/persistence/DurableMessageHistoryRepository.kt \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/message/DurableMessageHistory.kt \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresDurableMessageHistoryRepository.kt ||
    ! grep -Fq 'AND message.sequence < ?' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresDurableMessageHistoryRepository.kt ||
    ! grep -Fq 'ORDER BY message.sequence DESC' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresDurableMessageHistoryRepository.kt ||
    ! grep -Fq 'request.pageSize + 1' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresDurableMessageHistoryRepository.kt ||
    ! grep -Fq 'Connection.TRANSACTION_REPEATABLE_READ' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresDurableMessageHistoryRepository.kt ||
    ! grep -Fq 'connection.isReadOnly = true' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresDurableMessageHistoryRepository.kt ||
    ! grep -Fq 'NotFoundOrDenied' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/persistence/DurableMessageHistoryRepository.kt; then
    printf 'CI_STATIC_CONTRACT=FAIL\n' >&2
    printf 'ERROR=CONNECT_B6_MESSAGE_HISTORY_CONTRACT_MISMATCH\n' >&2
    exit 20
fi

if grep -En 'route\(|webSocket|WebSocket|/messages|/conversations' \
    src/postgresIntegrationTest/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresDurableRestartRecoveryIntegrationTest.kt ||
    grep -En 'compose[[:space:]]+down|down[[:space:]].*--volumes' \
        scripts/verify-durable-restart-recovery.sh ||
    ! grep -Fq 'run_recovery_phase SEED' scripts/verify-durable-restart-recovery.sh ||
    ! grep -Fq 'run_recovery_phase VERIFY' scripts/verify-durable-restart-recovery.sh ||
    ! grep -Fq 'compose rm --force app postgres' scripts/verify-durable-restart-recovery.sh ||
    ! grep -Fq 'DURABLE_STATE_HASH_PRESERVED=PASS' scripts/verify-durable-restart-recovery.sh ||
    ! grep -Fq 'DurableTextRepositoryResult.ReplayExisting' \
        src/postgresIntegrationTest/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresDurableRestartRecoveryIntegrationTest.kt ||
    ! grep -Fq 'ConversationCreationResult.Existing' \
        src/postgresIntegrationTest/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresDurableRestartRecoveryIntegrationTest.kt; then
    printf 'CI_STATIC_CONTRACT=FAIL\n' >&2
    printf 'ERROR=CONNECT_B7_DURABLE_RESTART_RECOVERY_CONTRACT_MISMATCH\n' >&2
    exit 20
fi

if ! grep -Fq 'io.ktor:ktor-version-catalog:3.5.2' settings.gradle.kts ||
    ! grep -Fq 'implementation(ktorLibs.server.websockets)' build.gradle.kts ||
    ! grep -Fq 'implementation(ktorLibs.server.auth)' build.gradle.kts ||
    ! grep -Fq 'webSocket("/v1/realtime")' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/routes/AuthenticatedRealtimeRoutes.kt ||
    ! grep -Fq 'authenticate(REALTIME_AUTH_PROVIDER)' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/routes/AuthenticatedRealtimeRoutes.kt ||
    ! grep -Fq 'maxFrameSize = RealtimeProtocol.MAX_TEXT_FRAME_BYTES' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/realtime/RealtimeTransport.kt ||
    ! grep -Fq 'ServerRealtimeFrameType.AUTH_OK' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/routes/AuthenticatedRealtimeRoutes.kt ||
    ! grep -Fq 'INCOMPATIBLE_PROTOCOL_MAJOR' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/realtime/RealtimeProtocol.kt ||
    ! grep -Fq 'BINARY_FRAMES_UNSUPPORTED' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/routes/AuthenticatedRealtimeRoutes.kt ||
    grep -En 'TYPING_(START|STOP)|PRESENCE_CHANGED|RESYNC_REQUIRED' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/realtime/RealtimeTransport.kt \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/routes/AuthenticatedRealtimeRoutes.kt \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/realtime/RealtimeProtocol.kt ||
    grep -En '(token|bearerToken|credential)[[:space:]]*:' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/realtime/RealtimeProtocol.kt; then
    printf 'CI_STATIC_CONTRACT=FAIL\n' >&2
    printf 'ERROR=CONNECT_C1_AUTHENTICATED_WEBSOCKET_CONTRACT_MISMATCH\n' >&2
    exit 20
fi

if ! grep -Fq 'ClientRealtimeFrameType.SUBSCRIBE_CONVERSATION' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/routes/AuthenticatedRealtimeRoutes.kt ||
    ! grep -Fq 'ServerRealtimeFrameType.CONVERSATION_SUBSCRIBED' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/routes/AuthenticatedRealtimeRoutes.kt ||
    ! grep -Fq 'RepositoryConversationSubscriptionAuthorizer' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/realtime/RealtimeTransport.kt ||
    ! grep -Fq 'OpenConversationRequest' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/realtime/ConversationSubscriptionAuthorizer.kt ||
    ! grep -Fq 'OpenConversationResult.NotFoundOrDenied' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/realtime/ConversationSubscriptionAuthorizer.kt ||
    ! grep -Fq 'ConversationParticipantStatus.ACTIVE' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/realtime/ConversationSubscriptionAuthorizer.kt ||
    ! grep -Fq 'withContext(Dispatchers.IO)' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/routes/AuthenticatedRealtimeRoutes.kt ||
    ! grep -Fq 'CONVERSATION_NOT_FOUND_OR_DENIED' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/routes/AuthenticatedRealtimeRoutes.kt ||
    ! grep -Fq 'MAX_CONVERSATION_SUBSCRIPTIONS = 100' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/realtime/RealtimeProtocol.kt ||
    grep -En 'TYPING_(START|STOP)|PRESENCE_CHANGED|RESYNC_REQUIRED' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/realtime/ConversationSubscriptionAuthorizer.kt \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/realtime/RealtimeTransport.kt \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/routes/AuthenticatedRealtimeRoutes.kt \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/realtime/RealtimeProtocol.kt ||
    grep -En '(token|bearerToken|credential)[[:space:]]*:' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/realtime/RealtimeProtocol.kt; then
    printf 'CI_STATIC_CONTRACT=FAIL\n' >&2
    printf 'ERROR=CONNECT_C2_AUTHORIZED_SUBSCRIPTION_CONTRACT_MISMATCH\n' >&2
    exit 20
fi

if ! grep -Fq 'post("/v1/conversations/{conversationRef}/messages")' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/routes/DurableTextMessageRoutes.kt ||
    ! grep -Fq 'DurableTextRepositoryResult.Committed' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/realtime/DurableTextMessageCoordinator.kt ||
    ! grep -Fq 'eventPublisher.publish(event)' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/realtime/DurableTextMessageCoordinator.kt ||
    ! grep -Fq 'ServerRealtimeFrameType.MESSAGE_CREATED' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/routes/AuthenticatedRealtimeRoutes.kt ||
    ! grep -Fq 'conversationEventHub.subscribe' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/routes/AuthenticatedRealtimeRoutes.kt ||
    ! grep -Fq 'authorizer.authorize' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/realtime/AuthorizedConversationEventHub.kt ||
    ! grep -Fq 'DurableTextRepositoryResult.ReplayExisting' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/routes/DurableTextMessageRoutes.kt ||
    grep -En 'TYPING_(START|STOP)|PRESENCE_CHANGED|RESYNC_REQUIRED' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/realtime/AuthorizedConversationEventHub.kt \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/realtime/DurableTextMessageCoordinator.kt \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/realtime/RealtimeTransport.kt \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/routes/AuthenticatedRealtimeRoutes.kt \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/routes/DurableTextMessageRoutes.kt \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/realtime/RealtimeProtocol.kt ||
    grep -REn '(Jedis|Lettuce|RedisClient|redis://)' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/realtime \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/realtime \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/routes ||
    grep -Fq 'io.ktor.client.request.contentType' \
        src/test/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/routes/AuthenticatedRealtimeRoutesTest.kt \
        src/test/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/routes/AuthenticatedRealtimeRuntimeTest.kt; then
    printf 'CI_STATIC_CONTRACT=FAIL\n' >&2
    printf 'ERROR=CONNECT_C3_DURABLE_MESSAGE_CREATED_CONTRACT_MISMATCH\n' >&2
    exit 20
fi

if ! grep -Fq 'val afterSequence: Long? = null' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/realtime/RealtimeProtocol.kt ||
    ! grep -Fq 'CONVERSATION_SYNCED' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/realtime/RealtimeProtocol.kt ||
    ! grep -Fq 'synchronizeConversation(conversationRef)' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/routes/AuthenticatedRealtimeRoutes.kt ||
    ! grep -Fq 'DurableConversationCatchUpResult.Loaded' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/routes/AuthenticatedRealtimeRoutes.kt ||
    ! grep -Fq 'PostgresDurableMessageHistoryRepository(dataSource)' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresDatabaseLifecycle.kt ||
    ! grep -Fq 'events.size > maxCatchUpMessages' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/realtime/DurableConversationCatchUp.kt ||
    ! grep -Fq 'DURABLE_CURRENT_CURSOR_DUPLICATE=0' \
        scripts/verify-durable-catch-up-resync.sh ||
    grep -REn '(Jedis|Lettuce|RedisClient|redis://)' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/realtime/DurableConversationCatchUp.kt \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/routes/AuthenticatedRealtimeRoutes.kt; then
    printf 'CI_STATIC_CONTRACT=FAIL\n' >&2
    printf 'ERROR=CONNECT_C4_DURABLE_CATCH_UP_CONTRACT_MISMATCH\n' >&2
    exit 20
fi

if ! grep -Fq 'const val ACK_DELIVERY = "ACK_DELIVERY"' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/realtime/RealtimeProtocol.kt ||
    ! grep -Fq 'const val UPDATE_READ_CURSOR = "UPDATE_READ_CURSOR"' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/realtime/RealtimeProtocol.kt ||
    ! grep -Fq 'GREATEST(' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresDurableReceiptCursorRepository.kt ||
    ! grep -Fq 'highest_read_sequence <= highest_delivered_sequence' \
        src/main/resources/db/migration/V5__durable_receipt_cursors.sql ||
    ! grep -Fq 'publishReceipt(event, excludedRegistration = registration)' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/routes/AuthenticatedRealtimeRoutes.kt ||
    ! grep -Fq 'DURABLE_RECEIPT_RECONNECT_SNAPSHOT=PASS' \
        scripts/verify-durable-receipts.sh ||
    grep -REn '(Jedis|Lettuce|RedisClient|redis://|TYPING_START|TYPING_STOP|PRESENCE_CHANGED)' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/realtime/DurableReceiptCursorService.kt \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresDurableReceiptCursorRepository.kt \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/routes/AuthenticatedRealtimeRoutes.kt; then
    printf 'CI_STATIC_CONTRACT=FAIL\n' >&2
    printf 'ERROR=CONNECT_C5_DURABLE_RECEIPT_CONTRACT_MISMATCH\n' >&2
    exit 20
fi

if ! grep -Fq 'class BoundedRealtimeOutboundSender' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/realtime/RealtimeTransportHardening.kt ||
    ! grep -Fq 'Channel<PendingFrame>(capacity)' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/realtime/RealtimeTransportHardening.kt ||
    ! grep -Fq 'withTimeout(sendTimeoutMillis)' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/realtime/RealtimeTransportHardening.kt ||
    ! grep -Fq 'SlowRealtimeConsumerException' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/realtime/RealtimeTransportHardening.kt ||
    ! grep -Fq 'RealtimeConnectionLimiter' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/realtime/RealtimeTransport.kt ||
    ! grep -Fq 'CONNECTION_LIMIT_REACHED' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/routes/AuthenticatedRealtimeRoutes.kt ||
    ! grep -Fq 'SLOW_CONSUMER' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/routes/AuthenticatedRealtimeRoutes.kt ||
    ! grep -Fq 'outboundSender.shutdown()' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/routes/AuthenticatedRealtimeRoutes.kt ||
    ! grep -Fq 'connectionLease.close()' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/routes/AuthenticatedRealtimeRoutes.kt ||
    ! grep -Fq 'LIVE_FAN_OUT_SCOPE = "SINGLE_APPLICATION_INSTANCE"' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/realtime/RealtimeProtocol.kt ||
    ! grep -Fq 'PING_PERIOD_SECONDS = 20' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/realtime/RealtimeProtocol.kt ||
    ! grep -Fq 'IDLE_TIMEOUT_SECONDS = 15' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/realtime/RealtimeProtocol.kt ||
    grep -REn '(Jedis|Lettuce|RedisClient|redis://|TYPING_START|TYPING_STOP|PRESENCE_CHANGED)' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/realtime/RealtimeTransportHardening.kt \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/routes/AuthenticatedRealtimeRoutes.kt; then
    printf 'CI_STATIC_CONTRACT=FAIL\n' >&2
    printf 'ERROR=CONNECT_C6_REALTIME_TRANSPORT_HARDENING_CONTRACT_MISMATCH\n' >&2
    exit 20
fi
printf 'CI_SECONDARY_SOURCE_SCAN=PASS\n'
printf 'CI_STATIC_CONTRACT=PASS\n'

if [[ -e "$ENV_FILE" || -L "$ENV_FILE" ]]; then
    if [[ ! -f "$ENV_FILE" || -L "$ENV_FILE" ]]; then
        printf 'ERROR=LOCAL_ENV_MUST_BE_A_REGULAR_FILE\n' >&2
        exit 20
    fi
    ENV_BEFORE_SHA="$(shasum -a 256 "$ENV_FILE" | awk '{print $1}')"
else
    ENV_CREATED=1
    make -s env
fi

if [[ ! -f "$ENV_FILE" || -L "$ENV_FILE" ]]; then
    printf 'ERROR=LOCAL_ENV_BOOTSTRAP_FAILED\n' >&2
    exit 21
fi

env_mode="$(stat -f '%Lp' "$ENV_FILE" 2>/dev/null || true)"
if [[ ! "$env_mode" =~ ^[0-9]{3}$ ]]; then
    env_mode="$(stat -c '%a' "$ENV_FILE" 2>/dev/null || true)"
fi
if [[ "$env_mode" != "600" ]]; then
    printf 'ERROR=LOCAL_ENV_PERMISSIONS_NOT_PRIVATE\n' >&2
    exit 22
fi

for exact_env in \
    'CONNECT_LAB_COMPOSE_PROJECT=nexo-connect-lab' \
    'CONNECT_LAB_HTTP_HOST_PORT=8282' \
    'CONNECT_LAB_POSTGRES_HOST_PORT=55432' \
    'CONNECT_LAB_REDIS_HOST_PORT=56379' \
    'CONNECT_LAB_MINIO_API_HOST_PORT=59000' \
    'CONNECT_LAB_MINIO_CONSOLE_HOST_PORT=59001' \
    'CONNECT_LAB_NEXO_INTEGRATION_ENABLED=false' \
    'CONNECT_LAB_NEXO_DB_DIRECT_ACCESS=false' \
    'CONNECT_LAB_CALLS_ENABLED=false' \
    'CONNECT_LAB_E2EE_CLAIM=false' \
    'CONNECT_LAB_DATABASE_LIFECYCLE_ENABLED=true' \
    'CONNECT_LAB_POSTGRES_APP_USER=nexo_connect_lab_app' \
    'CONNECT_LAB_POSTGRES_APP_MAX_POOL_SIZE=12'; do
    if ! grep -Fqx "$exact_env" "$ENV_FILE"; then
        printf 'ERROR=LOCAL_ENV_ISOLATION_CONTRACT_MISMATCH\n' >&2
        exit 23
    fi
done

business_token="$(awk -F= '$1 == "CONNECT_LAB_SYNTHETIC_BUSINESS_TOKEN" { print substr($0, index($0, "=") + 1); count++ } END { if (count != 1) exit 1 }' "$ENV_FILE")" || {
    printf 'ERROR=LOCAL_ENV_SYNTHETIC_IDENTITY_CONTRACT_MISMATCH\n' >&2
    exit 23
}
client_token="$(awk -F= '$1 == "CONNECT_LAB_SYNTHETIC_CLIENT_TOKEN" { print substr($0, index($0, "=") + 1); count++ } END { if (count != 1) exit 1 }' "$ENV_FILE")" || {
    printf 'ERROR=LOCAL_ENV_SYNTHETIC_IDENTITY_CONTRACT_MISMATCH\n' >&2
    exit 23
}
if [[ ${#business_token} -lt 32 || ${#client_token} -lt 32 || "$business_token" == "$client_token" ]]; then
    printf 'ERROR=LOCAL_ENV_SYNTHETIC_IDENTITY_CONTRACT_MISMATCH\n' >&2
    exit 23
fi
unset business_token client_token

if ! command -v docker >/dev/null 2>&1 || ! docker version >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
    printf 'ERROR=DOCKER_ENGINE_OR_COMPOSE_UNAVAILABLE\n' >&2
    exit 24
fi

if [[ "$(resource_count container)" != "0" || \
      "$(resource_count volume)" != "0" || \
      "$(resource_count network)" != "0" ]]; then
    printf 'ERROR=CI_COMPOSE_PROJECT_ALREADY_IN_USE\n' >&2
    exit 25
fi

compose --profile setup --profile migration config --quiet
printf 'COMPOSE_CONFIG=PASS\n'

./gradlew --no-daemon clean test --console=plain
printf 'GRADLE_CLEAN_TEST=PASS\n'

STACK_MAY_EXIST=1
compose up -d --build --wait --wait-timeout 180
printf 'COMPOSE_UP=PASS\n'

app_id="$(compose ps -q app)"
postgres_id="$(compose ps -q postgres)"
redis_id="$(compose ps -q redis)"
minio_id="$(compose ps -q minio)"

if [[ -z "$app_id" || -z "$postgres_id" || -z "$redis_id" || -z "$minio_id" ]]; then
    printf 'ERROR=EXPECTED_RUNNING_CONTAINER_MISSING\n' >&2
    exit 26
fi

if [[ "$(docker port "$app_id" 8282/tcp 2>/dev/null || true)" != "127.0.0.1:8282" || \
      "$(docker port "$postgres_id" 5432/tcp 2>/dev/null || true)" != "127.0.0.1:55432" || \
      "$(docker port "$redis_id" 6379/tcp 2>/dev/null || true)" != "127.0.0.1:56379" || \
      "$(docker port "$minio_id" 9000/tcp 2>/dev/null || true)" != "127.0.0.1:59000" || \
      "$(docker port "$minio_id" 9001/tcp 2>/dev/null || true)" != "127.0.0.1:59001" ]]; then
    printf 'ERROR=LOOPBACK_PORT_PUBLICATION_MISMATCH\n' >&2
    exit 27
fi
printf 'PORT_PUBLICATION=PASS\n'

if [[ "$(docker inspect --format '{{.Config.User}}' "$app_id")" != "10001:10001" || \
      "$(docker inspect --format '{{len .NetworkSettings.Networks}}' "$app_id")" != "1" || \
      "$(docker inspect --format '{{len .NetworkSettings.Networks}}' "$postgres_id")" != "1" || \
      "$(docker inspect --format '{{len .NetworkSettings.Networks}}' "$redis_id")" != "1" || \
      "$(docker inspect --format '{{len .NetworkSettings.Networks}}' "$minio_id")" != "1" ]]; then
    printf 'ERROR=RUNTIME_ISOLATION_MISMATCH\n' >&2
    exit 28
fi
printf 'RUNTIME_ISOLATION=PASS\n'

./scripts/smoke-local-stack.sh
printf 'STACK_SMOKE=PASS\n'

./scripts/verify-authenticated-websocket.sh
printf 'AUTHENTICATED_WEBSOCKET_CONTRACT=PASS\n'

./scripts/verify-authorized-conversation-subscriptions.sh
printf 'AUTHORIZED_CONVERSATION_SUBSCRIPTION_CONTRACT=PASS\n'

./scripts/verify-durable-message-created-events.sh
printf 'DURABLE_MESSAGE_CREATED_CONTRACT=PASS\n'

./scripts/verify-durable-catch-up-resync.sh
printf 'DURABLE_CATCH_UP_RESYNC_CONTRACT=PASS\n'

./scripts/verify-durable-receipts.sh
printf 'DURABLE_RECEIPT_CONTRACT=PASS\n'

./scripts/verify-realtime-transport-hardening.sh
printf 'REALTIME_TRANSPORT_HARDENING=PASS\n'

./scripts/verify-postgres-schema.sh
printf 'POSTGRES_SCHEMA_CONTRACT=PASS\n'

./scripts/verify-postgres-repository.sh
printf 'POSTGRES_REPOSITORY_CONTRACT=PASS\n'

./scripts/verify-durable-restart-recovery.sh
printf 'DURABLE_RESTART_RECOVERY_CONTRACT=PASS\n'

./scripts/verify-database-lifecycle.sh
printf 'DATABASE_LIFECYCLE_CONTRACT=PASS\n'
