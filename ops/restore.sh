#!/usr/bin/env bash
#
# ops/restore.sh — replay a backup file produced by ops/backup.sh.
#
# Each case in the backup is POSTed to /cases/{caseId}/follow-ups, so the
# backup's field values are merged into whatever the service currently holds.
# Read the "Semantics" note below before relying on this for recovery.
#
# Usage: ops/restore.sh [--dry-run] <backup-file>
#
# Environment overrides:
#   BASE_URL   service base URL (default http://localhost:8080)
#
# Semantics: this replays through the follow-up merge endpoint, which is the
# only write path the API exposes. Consequences worth knowing:
#   * Field VALUES converge on the backup — replaying is safe to repeat.
#   * Case METADATA does not: every replay increments the stored version and
#     rewrites extracted_at, because a merge always produces a new version.
#   * Fields added after the backup are RETAINED, not deleted. This merges a
#     backup forward; it does not roll a case back to its backed-up state.
#   * A case absent from the store cannot be recreated — the endpoint 404s.

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
cd -- "${REPO_ROOT}"

BASE_URL="${BASE_URL:-http://localhost:8080}"
DRY_RUN="false"
BACKUP_FILE=""

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" >&2; }

usage() {
	cat <<'EOF'
Usage: ops/restore.sh [--dry-run] <backup-file>

Replays each case in a backup file to /cases/{caseId}/follow-ups.

Options:
  --dry-run   Print what would be POSTed without making any request
  --help      Show this message
EOF
}

require_cmd() {
	if ! command -v "$1" > /dev/null 2>&1; then
		log "[ERROR] Required command not found: $1"
		exit 1
	fi
}

RESPONSE_FILE=""
cleanup() {
	if [ -n "${RESPONSE_FILE}" ]; then
		rm -f -- "${RESPONSE_FILE}"
	fi
}
trap cleanup EXIT

parse_args() {
	while [ "$#" -gt 0 ]; do
		case "$1" in
			--dry-run)
				DRY_RUN="true"
				shift
				;;
			--help | -h)
				usage
				exit 0
				;;
			-*)
				log "[ERROR] Unknown option: $1"
				usage >&2
				exit 1
				;;
			*)
				if [ -n "${BACKUP_FILE}" ]; then
					log "[ERROR] Unexpected extra argument: $1"
					usage >&2
					exit 1
				fi
				BACKUP_FILE="$1"
				shift
				;;
		esac
	done

	if [ -z "${BACKUP_FILE}" ]; then
		log "[ERROR] No backup file given"
		usage >&2
		exit 1
	fi
}

main() {
	parse_args "$@"

	require_cmd jq
	require_cmd curl

	if [ ! -f "${BACKUP_FILE}" ]; then
		log "[ERROR] Backup file not found: ${BACKUP_FILE}"
		exit 1
	fi

	# Reject a malformed or unexpected file before touching the service, so a
	# bad input can never leave a case half-restored.
	if ! jq -e 'type == "array"' "${BACKUP_FILE}" > /dev/null 2>&1; then
		log "[ERROR] ${BACKUP_FILE} is not a JSON array of cases"
		exit 1
	fi

	total="$(jq 'length' "${BACKUP_FILE}")"
	if [ "${total}" -eq 0 ]; then
		log "[ERROR] ${BACKUP_FILE} contains no cases"
		exit 1
	fi

	if [ "${DRY_RUN}" = "true" ]; then
		log "DRY RUN - no requests will be sent"
	fi
	log "Restoring ${total} case(s) from ${BACKUP_FILE} to ${BASE_URL}"

	# A dry run deliberately skips the reachability check: its whole purpose is
	# to be inspectable without a running service.
	if [ "${DRY_RUN}" != "true" ]; then
		if ! curl -sf -o /dev/null "${BASE_URL}/health"; then
			log "[ERROR] Service is not reachable at ${BASE_URL} - start it with: ops/run.sh start"
			exit 1
		fi
		RESPONSE_FILE="$(mktemp -t restore_response)"
	fi

	failures=0
	restored=0

	# Process substitution keeps the loop in this shell rather than a subshell,
	# so the counters survive the loop.
	while IFS= read -r case_json; do
		case_id="$(printf '%s' "${case_json}" | jq -r '.case_id // empty')"

		if [ -z "${case_id}" ]; then
			log "[ERROR] Entry has no case_id - skipping"
			failures=$((failures + 1))
			continue
		fi

		target="${BASE_URL}/cases/${case_id}/follow-ups"

		if [ "${DRY_RUN}" = "true" ]; then
			log "Would POST ${target}"
			log "  payload: $(printf '%s' "${case_json}" | jq -c '{case_id, version, case_classification, source_document, sections: (.sections | keys)}')"
			restored=$((restored + 1))
			continue
		fi

		log "POST ${target}"
		http_code="$(curl -sS -o "${RESPONSE_FILE}" -w '%{http_code}' \
			-X POST "${target}" \
			-H 'Content-Type: application/json' \
			--data-binary "${case_json}")" || http_code="000"

		if [ "${http_code}" = "200" ]; then
			summary="$(jq -c '.summary // empty' "${RESPONSE_FILE}" 2>/dev/null || true)"
			log "  OK ${case_id} (HTTP 200) ${summary}"
			restored=$((restored + 1))
		else
			log "  [ERROR] ${case_id} failed (HTTP ${http_code}): $(head -c 200 -- "${RESPONSE_FILE}" 2>/dev/null || true)"
			failures=$((failures + 1))
		fi
	done < <(jq -c '.[]' "${BACKUP_FILE}")

	log "Restored ${restored}/${total} case(s), ${failures} failure(s)"

	if [ "${failures}" -gt 0 ]; then
		exit 1
	fi
	exit 0
}

main "$@"
