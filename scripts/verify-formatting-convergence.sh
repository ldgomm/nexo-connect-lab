#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
RATCHET_REF="558d702bd5e7729721cde71d0e3080513798dcdd"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/connect_format_convergence.XXXXXX")"
GENERATED_PROBE_DIR="${PROJECT_DIR}/src/generated/connect02-format-probe"
BUILD_PROBE_DIR="${PROJECT_DIR}/build/connect02-format-probe"
GENERATED_PROBE="${GENERATED_PROBE_DIR}/GeneratedProbe.kt"
BUILD_PROBE="${BUILD_PROBE_DIR}/BuildProbe.kt"

fail() {
    printf 'FORMATTER_CONVERGENCE=FAIL\n' >&2
    printf 'ERROR=%s\n' "$1" >&2
    exit 1
}

cleanup() {
    local status=$?
    local cleanup_file
    trap - EXIT
    set +e
    for cleanup_file in \
        "$GENERATED_PROBE" "$BUILD_PROBE" \
        "$TEMP_DIR/before.diff" "$TEMP_DIR/first.diff" "$TEMP_DIR/second.diff" \
        "$TEMP_DIR/before.untracked" "$TEMP_DIR/first.untracked" "$TEMP_DIR/second.untracked"; do
        if [[ -e "$cleanup_file" || -L "$cleanup_file" ]]; then
            unlink "$cleanup_file" 2>/dev/null || true
        fi
    done
    rmdir "$GENERATED_PROBE_DIR" "$PROJECT_DIR/src/generated" \
        "$BUILD_PROBE_DIR" "$PROJECT_DIR/build" "$TEMP_DIR" 2>/dev/null || true
    exit "$status"
}

capture_state() {
    local label="$1"
    git diff --binary --no-ext-diff HEAD > "$TEMP_DIR/${label}.diff"
    git ls-files --others --exclude-standard | LC_ALL=C sort > "$TEMP_DIR/${label}.untracked"
}

catalog_value() {
    local key="$1"
    awk -F= -v expected="$key" '
        /^\[versions\]$/ { in_versions = 1; next }
        /^\[/ { in_versions = 0 }
        in_versions && $1 ~ "^[[:space:]]*" expected "[[:space:]]*$" {
            value = $2
            gsub(/[[:space:]\"]/, "", value)
            count++
        }
        END {
            if (count != 1) exit 2
            print value
        }
    ' gradle/libs.versions.toml
}

trap cleanup EXIT

cd "$PROJECT_DIR"

for required_command in git awk grep sort cmp shasum mktemp unlink; do
    command -v "$required_command" >/dev/null 2>&1 || fail "REQUIRED_COMMAND_MISSING:${required_command}"
done

[[ "$(git rev-parse --show-toplevel 2>/dev/null || true)" == "$PROJECT_DIR" ]] ||
    fail "REPOSITORY_IDENTITY_MISMATCH"
[[ -x ./gradlew && ! -L ./gradlew ]] || fail "GRADLE_WRAPPER_MISSING_OR_UNSAFE"
git cat-file -e "${RATCHET_REF}^{commit}" 2>/dev/null || fail "RATCHET_COMMIT_MISSING"
[[ "$(catalog_value spotless)" == "8.9.0" ]] || fail "SPOTLESS_VERSION_MISMATCH"
[[ "$(catalog_value ktlint)" == "1.8.0" ]] || fail "KTLINT_VERSION_MISMATCH"

for required_file in .editorconfig build.gradle.kts docs/governance/INTELLIJ_FORMATTING.md; do
    [[ -f "$required_file" && ! -L "$required_file" ]] || fail "FORMAT_FILE_MISSING_OR_UNSAFE:${required_file}"
done

grep -Fq "ratchetFrom(\"${RATCHET_REF}\")" build.gradle.kts || fail "RATCHET_CONFIGURATION_MISMATCH"
[[ "$(grep -Fc 'targetExclude("**/build/**", "**/generated/**")' build.gradle.kts)" == "2" ]] ||
    fail "FORMATTER_EXCLUSION_MISMATCH"

[[ ! -e "$GENERATED_PROBE_DIR" && ! -L "$GENERATED_PROBE_DIR" ]] ||
    fail "GENERATED_PROBE_PATH_ALREADY_EXISTS"
[[ ! -e "$BUILD_PROBE_DIR" && ! -L "$BUILD_PROBE_DIR" ]] ||
    fail "BUILD_PROBE_PATH_ALREADY_EXISTS"
mkdir -p "$GENERATED_PROBE_DIR" "$BUILD_PROBE_DIR"

printf 'package generated.probe\nclass  GeneratedProbe\n' > "$GENERATED_PROBE"
printf 'package build.probe\nclass  BuildProbe\n' > "$BUILD_PROBE"
generated_probe_sha="$(shasum -a 256 "$GENERATED_PROBE" | awk '{print $1}')"
build_probe_sha="$(shasum -a 256 "$BUILD_PROBE" | awk '{print $1}')"

capture_state before
./gradlew --no-daemon spotlessApply --console=plain
capture_state first

cmp -s "$TEMP_DIR/before.diff" "$TEMP_DIR/first.diff" ||
    fail "FIRST_FORMAT_PASS_CHANGED_CANONICAL_SOURCE"
cmp -s "$TEMP_DIR/before.untracked" "$TEMP_DIR/first.untracked" ||
    fail "FIRST_FORMAT_PASS_CHANGED_UNTRACKED_SCOPE"
[[ "$(shasum -a 256 "$GENERATED_PROBE" | awk '{print $1}')" == "$generated_probe_sha" ]] ||
    fail "GENERATED_SOURCE_WAS_FORMATTED"
[[ "$(shasum -a 256 "$BUILD_PROBE" | awk '{print $1}')" == "$build_probe_sha" ]] ||
    fail "BUILD_OUTPUT_WAS_FORMATTED"

./gradlew --no-daemon spotlessApply --console=plain
capture_state second

cmp -s "$TEMP_DIR/first.diff" "$TEMP_DIR/second.diff" || fail "FORMATTER_NOT_IDEMPOTENT"
cmp -s "$TEMP_DIR/first.untracked" "$TEMP_DIR/second.untracked" ||
    fail "SECOND_FORMAT_PASS_CHANGED_UNTRACKED_SCOPE"

./gradlew --no-daemon spotlessCheck --console=plain

printf 'FORMATTER_CONVERGENCE=PASS\n'
printf 'FIRST_PASS_DIFF=0\n'
printf 'SECOND_PASS_DIFF=0\n'
printf 'GENERATED_EXCLUSION=PASS\n'
printf 'BUILD_EXCLUSION=PASS\n'
