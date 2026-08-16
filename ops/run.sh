#!/usr/bin/env bash
#
# ops/run.sh — single entrypoint for operational tasks.
#
# Run `ops/run.sh --help` for the command list.

set -euo pipefail

# Resolve the repository root from this script's own location, then work from
# there. docker-compose.yml lives at the root while this script lives in ops/,
# so without this the commands would only work when invoked from one specific
# directory — the last thing anyone wants to discover at 2am.
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"

HEALTH_URL="http://localhost:8080/health"
HEALTH_ATTEMPTS=10
HEALTH_INTERVAL=3

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" >&2; }

usage() {
	cat <<'EOF'
Usage: ops/run.sh <command>

Commands:
  build   Build the Docker image
  start   Start the service and wait for healthy status
  stop    Stop the service
  test    Run unit tests and verify service health
  logs    Tail service logs
  clean   Remove containers, volumes, and local images
EOF
}

# Fail fast with an actionable message rather than letting the underlying tool
# emit something cryptic.
require_docker() {
	if ! docker info > /dev/null 2>&1; then
		echo "[ERROR] Docker is not running. Start Docker Desktop first." >&2
		exit 1
	fi
}

require_cmd() {
	if ! command -v "$1" > /dev/null 2>&1; then
		echo "[ERROR] Required command not found: $1" >&2
		exit 1
	fi
}

# Single health probe. Separated so `start` and `test` cannot drift apart in
# what they consider a healthy service.
health_ok() {
	curl -sf -o /dev/null "${HEALTH_URL}"
}

cmd_build() {
	require_docker
	log "Building Docker image..."
	docker-compose build
	log "Build complete"
}

cmd_start() {
	require_docker
	require_cmd curl

	log "Starting service..."
	docker-compose up -d

	log "Waiting for ${HEALTH_URL} (up to $((HEALTH_ATTEMPTS * HEALTH_INTERVAL))s)..."
	attempt=1
	while [ "${attempt}" -le "${HEALTH_ATTEMPTS}" ]; do
		if health_ok; then
			log "Service is UP"
			return 0
		fi
		log "  attempt ${attempt}/${HEALTH_ATTEMPTS} - not ready yet"
		if [ "${attempt}" -lt "${HEALTH_ATTEMPTS}" ]; then
			sleep "${HEALTH_INTERVAL}"
		fi
		attempt=$((attempt + 1))
	done

	log "Service failed to start - check logs with: ops/run.sh logs"
	return 1
}

cmd_stop() {
	require_docker
	log "Stopping service..."
	docker-compose down
	log "Service stopped"
}

# Both parts must pass. Unit tests run against the working tree and need no
# container; the health check needs a running service.
cmd_test() {
	require_cmd mvn
	require_cmd curl

	log "Running unit tests..."
	if ! mvn test -f backend/pom.xml; then
		log "Unit tests failed"
		return 1
	fi
	log "Unit tests passed"

	log "Checking service health..."
	if ! health_ok; then
		log "Service is not running - start it first with: ops/run.sh start"
		return 1
	fi
	log "Service health check passed"
}

cmd_logs() {
	require_docker
	log "Tailing service logs (Ctrl-C to stop)..."
	docker-compose logs -f
}

cmd_clean() {
	require_docker
	log "Removing containers, volumes, and local images..."
	docker-compose down -v --rmi local
	log "Cleaned up containers, volumes, and local images"
}

main() {
	if [ "$#" -eq 0 ]; then
		usage >&2
		exit 1
	fi

	subcommand="$1"

	case "${subcommand}" in
		--help | -h | help)
			usage
			exit 0
			;;
	esac

	cd -- "${REPO_ROOT}"

	case "${subcommand}" in
		build) cmd_build ;;
		start) cmd_start ;;
		stop) cmd_stop ;;
		test) cmd_test ;;
		logs) cmd_logs ;;
		clean) cmd_clean ;;
		*)
			echo "Unknown command: ${subcommand}" >&2
			usage >&2
			exit 1
			;;
	esac
}

main "$@"
