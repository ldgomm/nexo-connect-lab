#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
POLICY_FILE="${PROJECT_DIR}/docs/governance/connect-phase-policy.properties"
LEDGER_FILE="${PROJECT_DIR}/docs/governance/connect-phase-ledger.tsv"
OWNERSHIP_FILE="${PROJECT_DIR}/docs/governance/connect-ownership.properties"
STATE_FILE="${PROJECT_DIR}/docs/governance/CONNECT_PHASE_GOVERNANCE.md"
REQUIRE_EMPTY_TRACKED_WATCH=0

if [[ "${1:-}" == "--require-empty-tracked-watch" ]]; then
    REQUIRE_EMPTY_TRACKED_WATCH=1
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
require_property "$POLICY_FILE" baseline.phase CONNECT.USER.BASELINE
require_property "$POLICY_FILE" baseline.head 558d702bd5e7729721cde71d0e3080513798dcdd
require_property "$POLICY_FILE" baseline.parent e330359dc6602e9a33da891b5fdb64ed8c199f38
require_property "$POLICY_FILE" baseline.commit_count 31
require_property "$POLICY_FILE" baseline.subject .
require_property "$POLICY_FILE" accepted.phase CONNECT.21
require_property "$POLICY_FILE" accepted.head 106f8d6664f58f8e976315fd84f946ad2826c75e
require_property "$POLICY_FILE" establishing.phase CONNECT.22
require_property "$POLICY_FILE" next.phase CONNECT.23
require_property "$POLICY_FILE" commits.per.phase 1
require_property "$POLICY_FILE" intermediate.commits forbidden
require_property "$POLICY_FILE" commit.before.full_pass forbidden
require_property "$POLICY_FILE" commit.on_fail forbidden
require_property "$POLICY_FILE" user.preexisting.path docker-compose.watch.yml
require_property "$POLICY_FILE" user.preexisting.kind empty_tracked_file
require_property "$POLICY_FILE" user.preexisting.commit_inclusion accepted_user_baseline
require_property "$POLICY_FILE" ide.reformat.behaviour_change forbidden
require_property "$POLICY_FILE" ide.cleanup.progress_loss forbidden
require_property "$POLICY_FILE" ide.manual.reformat forbidden
require_property "$POLICY_FILE" ide.manual.optimize_imports forbidden
require_property "$POLICY_FILE" phase.start.worktree clean
require_property "$POLICY_FILE" formatter.plugin spotless
require_property "$POLICY_FILE" formatter.plugin.version 8.9.0
require_property "$POLICY_FILE" formatter.engine ktlint
require_property "$POLICY_FILE" formatter.engine.version 1.8.0
require_property "$POLICY_FILE" formatter.ratchet.from 558d702bd5e7729721cde71d0e3080513798dcdd
require_property "$POLICY_FILE" formatter.mass.reformat forbidden
require_property "$POLICY_FILE" formatter.generated.paths excluded
require_property "$POLICY_FILE" nexo.mutation.before_connect_46 forbidden
require_property "$POLICY_FILE" nexo.db.direct_access 0
require_property "$POLICY_FILE" database.sharing forbidden
require_property "$POLICY_FILE" connect.durable.truth postgresql
require_property "$POLICY_FILE" connect.redis.role ephemeral_live_fanout_presence_and_typing_leases
require_property "$POLICY_FILE" connect.exactly_once.claim false
require_property "$POLICY_FILE" connect.fanout.repair postgresql_sequence_and_authorised_catch_up
require_property "$POLICY_FILE" connect.presence.durable.history false
require_property "$POLICY_FILE" connect.presence.unauthorised.target.information 0
require_property "$POLICY_FILE" connect.presence.exact.last_seen false
require_property "$POLICY_FILE" connect.presence.lease.ttl.seconds 45
require_property "$POLICY_FILE" connect.presence.lease.refresh.seconds 15
require_property "$POLICY_FILE" connect.presence.lease.instance.ownership required
require_property "$POLICY_FILE" connect.presence.postgres.mutable.writes 0
require_property "$POLICY_FILE" connect.presence.aggregation any_active_device_lease
require_property "$POLICY_FILE" connect.presence.recently_online.window.seconds 900
require_property "$POLICY_FILE" connect.presence.device_topology.exposed false
require_property "$POLICY_FILE" connect.presence.clock_source redis_relative_ttl
require_property "$POLICY_FILE" connect.presence.denial.result silent_no_frame
require_property "$POLICY_FILE" connect.typing.durable.history false
require_property "$POLICY_FILE" connect.typing.lease.ttl.seconds 6
require_property "$POLICY_FILE" connect.typing.rate.limit.signals 6
require_property "$POLICY_FILE" connect.typing.rate.limit.window.seconds 3
require_property "$POLICY_FILE" connect.typing.conversation.authorization required
require_property "$POLICY_FILE" connect.typing.cross.conversation.leak 0
require_property "$POLICY_FILE" connect.typing.postgres.mutable.writes 0
require_property "$POLICY_FILE" connect.ephemeral.failure.injection \
    flushdb,clock_skew,duplicate_refresh,instance_crash,rapid_reconnect
require_property "$POLICY_FILE" connect.ephemeral.clock.source redis_relative_ttl
require_property "$POLICY_FILE" connect.ephemeral.stale.presence.keys 0
require_property "$POLICY_FILE" connect.ephemeral.stale.typing.keys 0
require_property "$POLICY_FILE" connect.ephemeral.durable.hash preserved
require_property "$POLICY_FILE" connect.push.registry.truth postgresql
require_property "$POLICY_FILE" connect.push.token.storage aes_256_gcm_ciphertext
require_property "$POLICY_FILE" connect.push.token.fingerprint hmac_sha256
require_property "$POLICY_FILE" connect.push.device.fingerprint hmac_sha256_scoped
require_property "$POLICY_FILE" connect.push.token.disclosure.count 0
require_property "$POLICY_FILE" connect.push.scope.ownership required
require_property "$POLICY_FILE" connect.push.tenant.crossover denied
require_property "$POLICY_FILE" connect.push.rotation version_fenced
require_property "$POLICY_FILE" connect.push.revocation cryptographic_erasure
require_property "$POLICY_FILE" connect.notification.outbox.truth postgresql
require_property "$POLICY_FILE" connect.notification.message.transaction atomic
require_property "$POLICY_FILE" connect.notification.intent.duplicate.count 0
require_property "$POLICY_FILE" connect.notification.payload.message.body false
require_property "$POLICY_FILE" connect.notification.payload.provider.token false
require_property "$POLICY_FILE" connect.notification.claim.strategy for_update_skip_locked
require_property "$POLICY_FILE" connect.notification.lease.version.fencing required
require_property "$POLICY_FILE" connect.notification.retry bounded
require_property "$POLICY_FILE" connect.notification.dead.letter required
require_property "$POLICY_FILE" connect.notification.provider.delivery deferred_connect_23

[[ "$(git rev-parse 558d702bd5e7729721cde71d0e3080513798dcdd^ 2>/dev/null || true)" == \
    "e330359dc6602e9a33da891b5fdb64ed8c199f38" ]] || fail "USER_BASELINE_PARENT_MISMATCH"
[[ "$(git rev-list --count 558d702bd5e7729721cde71d0e3080513798dcdd 2>/dev/null || true)" == "31" ]] ||
    fail "USER_BASELINE_COMMIT_COUNT_MISMATCH"
[[ "$(git show -s --format=%s 558d702bd5e7729721cde71d0e3080513798dcdd 2>/dev/null || true)" == "." ]] ||
    fail "USER_BASELINE_SUBJECT_MISMATCH"
[[ "$(git rev-parse 7e9e19a72571cfeb55bbd397e1a7adef6281f0ec^ 2>/dev/null || true)" == \
    "558d702bd5e7729721cde71d0e3080513798dcdd" ]] || fail "CONNECT_08_PARENT_MISMATCH"
[[ "$(git rev-list --count 7e9e19a72571cfeb55bbd397e1a7adef6281f0ec 2>/dev/null || true)" == "32" ]] ||
    fail "CONNECT_08_COMMIT_COUNT_MISMATCH"
[[ "$(git show -s --format=%s 7e9e19a72571cfeb55bbd397e1a7adef6281f0ec 2>/dev/null || true)" == \
    "chore(connect): [CONNECT.08] align IntelliJ and CI formatting" ]] || fail "CONNECT_08_SUBJECT_MISMATCH"
[[ "$(git rev-parse e13051b9d1f487e1978670bdaf608ead96d66486^ 2>/dev/null || true)" == \
    "7e9e19a72571cfeb55bbd397e1a7adef6281f0ec" ]] || fail "CONNECT_09_PARENT_MISMATCH"
[[ "$(git rev-list --count e13051b9d1f487e1978670bdaf608ead96d66486 2>/dev/null || true)" == "33" ]] ||
    fail "CONNECT_09_COMMIT_COUNT_MISMATCH"
[[ "$(git show -s --format=%s e13051b9d1f487e1978670bdaf608ead96d66486 2>/dev/null || true)" == \
    "test(connect): [CONNECT.09] harden semantic acceptance gates" ]] || fail "CONNECT_09_SUBJECT_MISMATCH"
[[ "$(git rev-parse b75633a445e6c7dc6bd686a0471f4078c616a14c^ 2>/dev/null || true)" == \
    "e13051b9d1f487e1978670bdaf608ead96d66486" ]] || fail "CONNECT_10_PARENT_MISMATCH"
[[ "$(git rev-list --count b75633a445e6c7dc6bd686a0471f4078c616a14c 2>/dev/null || true)" == "34" ]] ||
    fail "CONNECT_10_COMMIT_COUNT_MISMATCH"
[[ "$(git show -s --format=%s b75633a445e6c7dc6bd686a0471f4078c616a14c 2>/dev/null || true)" == \
    "test(connect): [CONNECT.10] establish realtime capacity baseline" ]] || fail "CONNECT_10_SUBJECT_MISMATCH"
[[ "$(git rev-parse 60a65ddbaa7de04ade07c3332c4d54a7b221d74f^ 2>/dev/null || true)" == \
    "b75633a445e6c7dc6bd686a0471f4078c616a14c" ]] || fail "CONNECT_11_PARENT_MISMATCH"
[[ "$(git rev-list --count 60a65ddbaa7de04ade07c3332c4d54a7b221d74f 2>/dev/null || true)" == "35" ]] ||
    fail "CONNECT_11_COMMIT_COUNT_MISMATCH"
[[ "$(git show -s --format=%s 60a65ddbaa7de04ade07c3332c4d54a7b221d74f 2>/dev/null || true)" == \
    "docs(connect): [CONNECT.11] freeze multi-instance fanout architecture" ]] || fail "CONNECT_11_SUBJECT_MISMATCH"
[[ "$(git rev-parse 655d733b8668011815f66e065d42c5d66c9e555e^ 2>/dev/null || true)" == \
    "60a65ddbaa7de04ade07c3332c4d54a7b221d74f" ]] || fail "CONNECT_12_PARENT_MISMATCH"
[[ "$(git rev-list --count 655d733b8668011815f66e065d42c5d66c9e555e 2>/dev/null || true)" == "36" ]] ||
    fail "CONNECT_12_COMMIT_COUNT_MISMATCH"
[[ "$(git show -s --format=%s 655d733b8668011815f66e065d42c5d66c9e555e 2>/dev/null || true)" == \
    "feat(connect): [CONNECT.12] add isolated ephemeral Redis boundary" ]] || fail "CONNECT_12_SUBJECT_MISMATCH"
[[ "$(git rev-parse 6442ef285a30f7836d75bba4ddb4452fb56a7a39^ 2>/dev/null || true)" == \
    "655d733b8668011815f66e065d42c5d66c9e555e" ]] || fail "CONNECT_13_PARENT_MISMATCH"
[[ "$(git rev-list --count 6442ef285a30f7836d75bba4ddb4452fb56a7a39 2>/dev/null || true)" == "37" ]] ||
    fail "CONNECT_13_COMMIT_COUNT_MISMATCH"
[[ "$(git show -s --format=%s 6442ef285a30f7836d75bba4ddb4452fb56a7a39 2>/dev/null || true)" == \
    "feat(connect): [CONNECT.13] add multi-instance realtime fanout" ]] || fail "CONNECT_13_SUBJECT_MISMATCH"
[[ "$(git rev-parse 92178a12832024b3019e583392fb3a4edee608e7^ 2>/dev/null || true)" == \
    "6442ef285a30f7836d75bba4ddb4452fb56a7a39" ]] || fail "CONNECT_14_PARENT_MISMATCH"
[[ "$(git rev-list --count 92178a12832024b3019e583392fb3a4edee608e7 2>/dev/null || true)" == "38" ]] ||
    fail "CONNECT_14_COMMIT_COUNT_MISMATCH"
[[ "$(git show -s --format=%s 92178a12832024b3019e583392fb3a4edee608e7 2>/dev/null || true)" == \
    "feat(connect): [CONNECT.14] coordinate multi-device realtime sessions" ]] || fail "CONNECT_14_SUBJECT_MISMATCH"
[[ "$(git rev-parse ae661a58465a3503d89f75019de76f249e76bbdb^ 2>/dev/null || true)" == \
    "92178a12832024b3019e583392fb3a4edee608e7" ]] || fail "CONNECT_15_PARENT_MISMATCH"
[[ "$(git rev-list --count ae661a58465a3503d89f75019de76f249e76bbdb 2>/dev/null || true)" == "39" ]] ||
    fail "CONNECT_15_COMMIT_COUNT_MISMATCH"
[[ "$(git show -s --format=%s ae661a58465a3503d89f75019de76f249e76bbdb 2>/dev/null || true)" == \
    "test(connect): [CONNECT.15] prove Redis loss recovery" ]] || fail "CONNECT_15_SUBJECT_MISMATCH"
[[ "$(git rev-parse 1478ce9f5e7348c5ac72ff3be457ccdc15ca42f9^ 2>/dev/null || true)" == \
    "ae661a58465a3503d89f75019de76f249e76bbdb" ]] || fail "CONNECT_16_PARENT_MISMATCH"
[[ "$(git rev-list --count 1478ce9f5e7348c5ac72ff3be457ccdc15ca42f9 2>/dev/null || true)" == "40" ]] ||
    fail "CONNECT_16_COMMIT_COUNT_MISMATCH"
[[ "$(git show -s --format=%s 1478ce9f5e7348c5ac72ff3be457ccdc15ca42f9 2>/dev/null || true)" == \
    "docs(connect): [CONNECT.16] freeze presence privacy contract" ]] || fail "CONNECT_16_SUBJECT_MISMATCH"
[[ "$(git rev-parse dda283df8687030d9efcc25ad504766690ceef93^ 2>/dev/null || true)" == \
    "1478ce9f5e7348c5ac72ff3be457ccdc15ca42f9" ]] || fail "CONNECT_17_PARENT_MISMATCH"
[[ "$(git rev-list --count dda283df8687030d9efcc25ad504766690ceef93 2>/dev/null || true)" == "41" ]] ||
    fail "CONNECT_17_COMMIT_COUNT_MISMATCH"
[[ "$(git show -s --format=%s dda283df8687030d9efcc25ad504766690ceef93 2>/dev/null || true)" == \
    "feat(connect): [CONNECT.17] implement ephemeral presence leases" ]] || fail "CONNECT_17_SUBJECT_MISMATCH"
[[ "$(git rev-parse 7273f10394d5c58831e27007a3c5cee4b3451987^ 2>/dev/null || true)" == \
    "dda283df8687030d9efcc25ad504766690ceef93" ]] || fail "CONNECT_18_PARENT_MISMATCH"
[[ "$(git rev-list --count 7273f10394d5c58831e27007a3c5cee4b3451987 2>/dev/null || true)" == "42" ]] ||
    fail "CONNECT_18_COMMIT_COUNT_MISMATCH"
[[ "$(git show -s --format=%s 7273f10394d5c58831e27007a3c5cee4b3451987 2>/dev/null || true)" == \
    "feat(connect): [CONNECT.18] implement bounded typing signals" ]] || fail "CONNECT_18_SUBJECT_MISMATCH"
[[ "$(git rev-parse 7fa02c47827380bda419f426dc09f01b5bd58c47^ 2>/dev/null || true)" == \
    "7273f10394d5c58831e27007a3c5cee4b3451987" ]] || fail "CONNECT_19_PARENT_MISMATCH"
[[ "$(git rev-list --count 7fa02c47827380bda419f426dc09f01b5bd58c47 2>/dev/null || true)" == "43" ]] ||
    fail "CONNECT_19_COMMIT_COUNT_MISMATCH"
[[ "$(git show -s --format=%s 7fa02c47827380bda419f426dc09f01b5bd58c47 2>/dev/null || true)" == \
    "feat(connect): [CONNECT.19] aggregate privacy-aware presence" ]] || fail "CONNECT_19_SUBJECT_MISMATCH"
[[ "$(git rev-parse 3c96ce2790ba641afc28e15b662569d2204b36fa^ 2>/dev/null || true)" == \
    "7fa02c47827380bda419f426dc09f01b5bd58c47" ]] || fail "CONNECT_20_PARENT_MISMATCH"
[[ "$(git rev-list --count 3c96ce2790ba641afc28e15b662569d2204b36fa 2>/dev/null || true)" == "44" ]] ||
    fail "CONNECT_20_COMMIT_COUNT_MISMATCH"
[[ "$(git show -s --format=%s 3c96ce2790ba641afc28e15b662569d2204b36fa 2>/dev/null || true)" == \
    "test(connect): [CONNECT.20] prove ephemeral signal resilience" ]] || fail "CONNECT_20_SUBJECT_MISMATCH"
[[ "$(git rev-parse 106f8d6664f58f8e976315fd84f946ad2826c75e^ 2>/dev/null || true)" == \
    "3c96ce2790ba641afc28e15b662569d2204b36fa" ]] || fail "CONNECT_21_PARENT_MISMATCH"
[[ "$(git rev-list --count 106f8d6664f58f8e976315fd84f946ad2826c75e 2>/dev/null || true)" == "45" ]] ||
    fail "CONNECT_21_COMMIT_COUNT_MISMATCH"
[[ "$(git show -s --format=%s 106f8d6664f58f8e976315fd84f946ad2826c75e 2>/dev/null || true)" == \
    "feat(connect): [CONNECT.21] add protected push device registry" ]] || fail "CONNECT_21_SUBJECT_MISMATCH"

require_property "$OWNERSHIP_FILE" manifest.version 1
require_property "$OWNERSHIP_FILE" nexo_core.owns identity,business,branch,products,orders,payments,inventory,fiscal,accounting
require_property "$OWNERSHIP_FILE" connect.owns conversations,participants,messages,sequences,receipts,presence,typing,media_metadata,push_device_registrations,notification_outbox
require_property "$OWNERSHIP_FILE" ai_lab.owns interpretation,orchestration,authorised_handoff
require_property "$OWNERSHIP_FILE" connect.postgres.durable_truth true
require_property "$OWNERSHIP_FILE" connect.redis.durable_truth false
require_property "$OWNERSHIP_FILE" object_storage.private_media_bytes true
require_property "$OWNERSHIP_FILE" connect.nexo_db_direct_access 0
require_property "$OWNERSHIP_FILE" connect.nexo_business_mutation forbidden
require_property "$OWNERSHIP_FILE" connect.country_specific_legal_logic forbidden
require_property "$OWNERSHIP_FILE" integration.first_phase CONNECT.46

expected_phases="CONNECT.B CONNECT.C1 CONNECT.C2 CONNECT.C3 CONNECT.C4 CONNECT.C5 CONNECT.C6 CONNECT.07 CONNECT.USER.BASELINE CONNECT.08 CONNECT.09 CONNECT.10 CONNECT.11 CONNECT.12 CONNECT.13 CONNECT.14 CONNECT.15 CONNECT.16 CONNECT.17 CONNECT.18 CONNECT.19 CONNECT.20 CONNECT.21"
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
            [[ "$phase" == "CONNECT.22" && "$status" == "IMPLEMENTING" && "$commit" == "DISCOVER_BY_SUBJECT" ]] ||
                fail "LEDGER_CURRENT_MISMATCH"
            current_subject="$subject"
            ;;
        NEXT)
            next_count=$((next_count + 1))
            [[ "$phase" == "CONNECT.23" && "$status" == "LOCKED" && "$commit" == "-" ]] ||
                fail "LEDGER_NEXT_MISMATCH"
            ;;
        "")
            ;;
        *)
            fail "LEDGER_RECORD_UNKNOWN:${record}"
            ;;
    esac
done < "$LEDGER_FILE"

[[ "$baseline_count" -eq 23 && "$actual_phases" == "$expected_phases" ]] ||
    fail "LEDGER_BASELINE_SEQUENCE_MISMATCH"
[[ "$current_count" -eq 1 && "$next_count" -eq 1 ]] || fail "LEDGER_PHASE_CARDINALITY_MISMATCH"

connect_22_matches="$(git log HEAD --format='%H%x09%s' | awk -F '\t' -v expected="$current_subject" '$2 == expected { print $1 }')"
connect_22_count="$(printf '%s\n' "$connect_22_matches" | awk 'NF { count++ } END { print count + 0 }')"

if [[ "$connect_22_count" == "0" ]]; then
    [[ "$(git rev-parse HEAD)" == "106f8d6664f58f8e976315fd84f946ad2826c75e" ]] ||
        fail "CONNECT_22_COMMIT_MISSING_AFTER_BASELINE"
elif [[ "$connect_22_count" == "1" ]]; then
    connect_22_commit="$connect_22_matches"
    [[ "$(git rev-parse "${connect_22_commit}^")" == "106f8d6664f58f8e976315fd84f946ad2826c75e" ]] ||
        fail "CONNECT_22_PARENT_MISMATCH"
    [[ "$(git rev-list --count "$connect_22_commit")" == "46" ]] || fail "CONNECT_22_COMMIT_COUNT_MISMATCH"
    git merge-base --is-ancestor "$connect_22_commit" HEAD || fail "CONNECT_22_NOT_ANCESTOR"
else
    fail "CONNECT_22_COMMIT_NOT_UNIQUE"
fi

if [[ "$REQUIRE_EMPTY_TRACKED_WATCH" -eq 1 ]]; then
    watch_path="$(property_value "$POLICY_FILE" user.preexisting.path)" ||
        fail "WATCH_POLICY_MISSING"
    [[ -f "$watch_path" && ! -L "$watch_path" && ! -s "$watch_path" ]] ||
        fail "WATCH_WORKTREE_NOT_EMPTY_REGULAR_FILE"
    git diff --quiet -- "$watch_path" || fail "WATCH_HAS_WORKTREE_CHANGE"
    git diff --cached --quiet -- "$watch_path" || fail "WATCH_HAS_INDEX_CHANGE"
    watch_index="$(git ls-files --stage -- "$watch_path")"
    [[ "$watch_index" == "100644 e69de29bb2d1d6434b8b29ae775ad8c2e48c5391 0"$'\t'"$watch_path" ]] ||
        fail "WATCH_INDEX_NOT_EMPTY_TRACKED_FILE"
    [[ "$(git rev-parse "HEAD:${watch_path}" 2>/dev/null || true)" == \
        "e69de29bb2d1d6434b8b29ae775ad8c2e48c5391" ]] || fail "WATCH_HEAD_NOT_EMPTY_TRACKED_FILE"
fi

printf 'PHASE_GOVERNANCE=PASS\n'
printf 'BASELINE_CONNECT_07=PASS\n'
printf 'USER_BASELINE=PASS\n'
printf 'ACCEPTED_CONNECT_21=PASS\n'
printf 'OWNERSHIP_MANIFEST=PASS\n'
printf 'ONE_COMMIT_POLICY=PASS\n'
printf 'MANUAL_IDE_ACTIONS=FORBIDDEN\n'
printf 'WATCH_PROTECTION=%s\n' "$([[ "$REQUIRE_EMPTY_TRACKED_WATCH" -eq 1 ]] && printf PASS || printf NOT_REQUIRED)"
