#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
POLICY_FILE="${PROJECT_DIR}/docs/governance/connect-phase-policy.properties"
LEDGER_FILE="${PROJECT_DIR}/docs/governance/connect-phase-ledger.tsv"
OWNERSHIP_FILE="${PROJECT_DIR}/docs/governance/connect-ownership.properties"
STATE_FILE="${PROJECT_DIR}/docs/governance/CONNECT_PHASE_GOVERNANCE.md"
REQUIRE_EMPTY_STAGED_WATCH=0

if [[ "${1:-}" == "--require-empty-staged-watch" ]]; then
    REQUIRE_EMPTY_STAGED_WATCH=1
elif [[ $# -ne 0 ]]; then
    printf 'PHASE_GOVERNANCE=FAIL\n' >&2
    printf 'ERROR=UNSUPPORTED_ARGUMENT\n' >&2
    exit 2
fi

fail() {
    printf 'PHASE_GOVERNANCE=FAIL\n' >&2
    printf 'ERROR=%s\n' "$1" >&2
    exit 1
}

property_value() {
    local file="$1"
    local key="$2"
    awk -F= -v target="$key" '
        $1 == target {
            count++
            value = substr($0, index($0, "=") + 1)
        }
        END {
            if (count != 1) exit 2
            print value
        }
    ' "$file"
}

require_property() {
    local file="$1"
    local key="$2"
    local expected="$3"
    local actual
    actual="$(property_value "$file" "$key")" || fail "PROPERTY_MISSING_OR_DUPLICATE:${key}"
    [[ "$actual" == "$expected" ]] || fail "PROPERTY_VALUE_MISMATCH:${key}"
}

cd "$PROJECT_DIR"

[[ "$(git rev-parse --show-toplevel 2>/dev/null || true)" == "$PROJECT_DIR" ]] ||
    fail "REPOSITORY_IDENTITY_MISMATCH"

for required_file in "$POLICY_FILE" "$LEDGER_FILE" "$OWNERSHIP_FILE" "$STATE_FILE"; do
    [[ -f "$required_file" && ! -L "$required_file" ]] ||
        fail "GOVERNANCE_FILE_MISSING_OR_UNSAFE:${required_file#"${PROJECT_DIR}/"}"
done

require_property "$POLICY_FILE" programme NEXO_CONNECT_LAB
require_property "$POLICY_FILE" policy.version 1
require_property "$POLICY_FILE" repository connect-lab
require_property "$POLICY_FILE" branch main
require_property "$POLICY_FILE" baseline.phase CONNECT.C6
require_property "$POLICY_FILE" baseline.head 8939caf34d603ee650e62f6a591050e1dbad35a8
require_property "$POLICY_FILE" baseline.commit_count 29
require_property "$POLICY_FILE" establishing.phase CONNECT.01
require_property "$POLICY_FILE" next.phase CONNECT.02
require_property "$POLICY_FILE" commits.per.phase 1
require_property "$POLICY_FILE" intermediate.commits forbidden
require_property "$POLICY_FILE" commit.before.full_pass forbidden
require_property "$POLICY_FILE" commit.on_fail forbidden
require_property "$POLICY_FILE" user.preexisting.path docker-compose.watch.yml
require_property "$POLICY_FILE" user.preexisting.kind empty_staged_file
require_property "$POLICY_FILE" user.preexisting.commit_inclusion forbidden
require_property "$POLICY_FILE" ide.reformat.behaviour_change forbidden
require_property "$POLICY_FILE" ide.cleanup.progress_loss forbidden
require_property "$POLICY_FILE" nexo.mutation.before_connect_46 forbidden
require_property "$POLICY_FILE" nexo.db.direct_access 0
require_property "$POLICY_FILE" database.sharing forbidden

require_property "$OWNERSHIP_FILE" manifest.version 1
require_property "$OWNERSHIP_FILE" nexo_core.owns identity,business,branch,products,orders,payments,inventory,fiscal,accounting
require_property "$OWNERSHIP_FILE" connect.owns conversations,participants,messages,sequences,receipts,presence,typing,media_metadata
require_property "$OWNERSHIP_FILE" ai_lab.owns interpretation,orchestration,authorised_handoff
require_property "$OWNERSHIP_FILE" connect.postgres.durable_truth true
require_property "$OWNERSHIP_FILE" connect.redis.durable_truth false
require_property "$OWNERSHIP_FILE" object_storage.private_media_bytes true
require_property "$OWNERSHIP_FILE" connect.nexo_db_direct_access 0
require_property "$OWNERSHIP_FILE" connect.nexo_business_mutation forbidden
require_property "$OWNERSHIP_FILE" connect.country_specific_legal_logic forbidden
require_property "$OWNERSHIP_FILE" integration.first_phase CONNECT.46

expected_phases="CONNECT.B CONNECT.C1 CONNECT.C2 CONNECT.C3 CONNECT.C4 CONNECT.C5 CONNECT.C6"
actual_phases=""
baseline_count=0
current_count=0
next_count=0
current_subject=""

while IFS=$'\t' read -r record phase status commit subject; do
    if [[ "$record" == "record" ]]; then
        [[ "$phase" == "phase" && "$status" == "status" && "$commit" == "commit" && "$subject" == "subject" ]] ||
            fail "LEDGER_HEADER_MISMATCH"
        continue
    fi

    case "$record" in
        BASELINE)
            [[ "$status" == "PASS" ]] || fail "LEDGER_BASELINE_NOT_PASS:${phase}"
            [[ "$commit" =~ ^[0-9a-f]{40}$ ]] || fail "LEDGER_COMMIT_INVALID:${phase}"
            git cat-file -e "${commit}^{commit}" 2>/dev/null || fail "LEDGER_COMMIT_MISSING:${phase}"
            git merge-base --is-ancestor "$commit" HEAD || fail "LEDGER_COMMIT_NOT_ANCESTOR:${phase}"
            [[ "$(git show -s --format=%s "$commit")" == "$subject" ]] ||
                fail "LEDGER_SUBJECT_MISMATCH:${phase}"
            actual_phases="${actual_phases}${actual_phases:+ }${phase}"
            baseline_count=$((baseline_count + 1))
            ;;
        CURRENT)
            current_count=$((current_count + 1))
            [[ "$phase" == "CONNECT.01" && "$status" == "IMPLEMENTING" && "$commit" == "DISCOVER_BY_SUBJECT" ]] ||
                fail "LEDGER_CURRENT_MISMATCH"
            current_subject="$subject"
            ;;
        NEXT)
            next_count=$((next_count + 1))
            [[ "$phase" == "CONNECT.02" && "$status" == "LOCKED" && "$commit" == "-" ]] ||
                fail "LEDGER_NEXT_MISMATCH"
            ;;
        "")
            ;;
        *)
            fail "LEDGER_RECORD_UNKNOWN:${record}"
            ;;
    esac
done < "$LEDGER_FILE"

[[ "$baseline_count" -eq 7 && "$actual_phases" == "$expected_phases" ]] ||
    fail "LEDGER_BASELINE_SEQUENCE_MISMATCH"
[[ "$current_count" -eq 1 && "$next_count" -eq 1 ]] || fail "LEDGER_PHASE_CARDINALITY_MISMATCH"

connect_01_matches="$(git log HEAD --format='%H%x09%s' | awk -F '\t' -v expected="$current_subject" '$2 == expected { print $1 }')"
connect_01_count="$(printf '%s\n' "$connect_01_matches" | awk 'NF { count++ } END { print count + 0 }')"

if [[ "$connect_01_count" == "0" ]]; then
    [[ "$(git rev-parse HEAD)" == "8939caf34d603ee650e62f6a591050e1dbad35a8" ]] ||
        fail "CONNECT_01_COMMIT_MISSING_AFTER_BASELINE"
elif [[ "$connect_01_count" == "1" ]]; then
    connect_01_commit="$connect_01_matches"
    [[ "$(git rev-parse "${connect_01_commit}^")" == "8939caf34d603ee650e62f6a591050e1dbad35a8" ]] ||
        fail "CONNECT_01_PARENT_MISMATCH"
    git merge-base --is-ancestor "$connect_01_commit" HEAD || fail "CONNECT_01_NOT_ANCESTOR"
else
    fail "CONNECT_01_COMMIT_NOT_UNIQUE"
fi

if [[ "$REQUIRE_EMPTY_STAGED_WATCH" -eq 1 ]]; then
    watch_path="$(property_value "$POLICY_FILE" user.preexisting.path)" ||
        fail "WATCH_POLICY_MISSING"
    [[ -f "$watch_path" && ! -L "$watch_path" && ! -s "$watch_path" ]] ||
        fail "WATCH_WORKTREE_NOT_EMPTY_REGULAR_FILE"
    git diff --quiet -- "$watch_path" || fail "WATCH_HAS_UNSTAGED_CHANGE"
    watch_index="$(git ls-files --stage -- "$watch_path")"
    [[ "$watch_index" == "100644 e69de29bb2d1d6434b8b29ae775ad8c2e48c5391 0"$'\t'"$watch_path" ]] ||
        fail "WATCH_INDEX_NOT_EMPTY_STAGED_FILE"
    if git cat-file -e "HEAD:${watch_path}" 2>/dev/null; then
        fail "WATCH_ALREADY_TRACKED_IN_HEAD"
    fi
fi

printf 'PHASE_GOVERNANCE=PASS\n'
printf 'BASELINE_C6=PASS\n'
printf 'OWNERSHIP_MANIFEST=PASS\n'
printf 'ONE_COMMIT_POLICY=PASS\n'
printf 'WATCH_PROTECTION=%s\n' "$([[ "$REQUIRE_EMPTY_STAGED_WATCH" -eq 1 ]] && printf PASS || printf NOT_REQUIRED)"
