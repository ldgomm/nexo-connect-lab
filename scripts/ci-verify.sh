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
        if compose --profile setup down --volumes --remove-orphans; then
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
    .dockerignore
    .env.example
    .env.host.example
    .github/workflows/ci.yml
    .gitignore
    Dockerfile
    Makefile
    build.gradle.kts
    compose.yaml
    gradlew
    scripts/ci-verify.sh
    scripts/generate-local-env.sh
    scripts/smoke-local-stack.sh
    settings.gradle.kts
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
    '(mongodb\+srv://|postgres(ql)?://|redis://|localhost:8080|@nexo\.test|NexoSuper|NexoStaging|BEGIN [A-Z ]*PRIVATE KEY|sk-[A-Za-z0-9_-]{16,})' \
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
if grep -Eq 'POSTGRES_PASSWORD|REDIS_PASSWORD|MINIO_ROOT_(USER|PASSWORD)' <<<"$app_block"; then
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
bash -n scripts/ci-verify.sh
git diff --check
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
    'CONNECT_LAB_E2EE_CLAIM=false'; do
    if ! grep -Fqx "$exact_env" "$ENV_FILE"; then
        printf 'ERROR=LOCAL_ENV_ISOLATION_CONTRACT_MISMATCH\n' >&2
        exit 23
    fi
done

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

compose --profile setup config --quiet
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
