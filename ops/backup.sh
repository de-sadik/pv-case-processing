#!/usr/bin/env bash
#
# ops/backup.sh — snapshot stored cases to a timestamped JSON array.
#
# Non-interactive and safe to run from cron. Environment overrides:
#   BASE_URL     service base URL      (default http://localhost:8080)
#   BACKUP_DIR   output directory      (default <repo>/backups)

set -euo pipefail

# Resolve the repository root from this script's own location and work from
# there, so a relative BACKUP_DIR always lands in the same place. Cron runs with
# the invoking user's home as the working directory, not the repo.
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
cd -- "${REPO_ROOT}"

BASE_URL="${BASE_URL:-http://localhost:8080}"
CASE_IDS=("PV-2026-0451")   # extend this list for more cases
BACKUP_DIR="${BACKUP_DIR:-backups}"
TIMESTAMP="$(date +%Y%m%d_%H%M%S)"
OUTFILE="${BACKUP_DIR}/backup_${TIMESTAMP}.json"

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" >&2; }

require_cmd() {
	if ! command -v "$1" > /dev/null 2>&1; then
		log "[ERROR] Required command not found: $1"
		exit 1
	fi
}

# Scratch files are removed on every exit path, so an aborted run leaves no
# debris and — crucially — no half-written file that looks like a real backup.
TMPFILE=""
cleanup() {
	if [ -n "${TMPFILE}" ]; then
		rm -f -- "${TMPFILE}" "${TMPFILE}.json"
	fi
}
trap cleanup EXIT

main() {
	log "Starting backup from ${BASE_URL}"

	require_cmd curl
	require_cmd jq

	# Guard the empty case: under `set -u`, bash 3.2 treats "${CASE_IDS[@]}" on
	# an empty array as an unbound variable, which would abort with a message
	# that says nothing about the real problem.
	if [ "${#CASE_IDS[@]}" -eq 0 ]; then
		log "[ERROR] CASE_IDS is empty - nothing to back up"
		exit 1
	fi

	if ! curl -sf -o /dev/null "${BASE_URL}/health"; then
		log "[ERROR] Service is not reachable at ${BASE_URL} - start it with: ops/run.sh start"
		exit 1
	fi

	mkdir -p -- "${BACKUP_DIR}"

	# Staged inside BACKUP_DIR rather than /tmp so the final mv is a same
	# filesystem rename, and therefore atomic: readers see either no file or a
	# complete one, never a partial write.
	TMPFILE="$(mktemp "${BACKUP_DIR}/.backup_${TIMESTAMP}.XXXXXX")"

	for case_id in "${CASE_IDS[@]}"; do
		log "Fetching ${case_id}"
		if ! curl -sf "${BASE_URL}/cases/${case_id}" >> "${TMPFILE}"; then
			log "[ERROR] Failed to fetch ${case_id} - aborting without writing a backup"
			exit 1
		fi
		printf '\n' >> "${TMPFILE}"
	done

	# jq -s slurps the concatenated objects into one array. It also parses every
	# response, so a 200 carrying malformed JSON fails here rather than producing
	# a backup that only reveals itself as broken during a restore.
	if ! jq -s '.' "${TMPFILE}" > "${TMPFILE}.json"; then
		log "[ERROR] Response was not valid JSON - aborting without writing a backup"
		exit 1
	fi

	mv -- "${TMPFILE}.json" "${OUTFILE}"

	log "Backed up ${#CASE_IDS[@]} case(s)"
	log "Backup written to ${OUTFILE}"
	exit 0
}

main "$@"
