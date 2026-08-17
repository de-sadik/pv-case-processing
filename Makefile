# Makefile — thin wrapper over the ops/ scripts.
#
# Every target delegates rather than reimplementing anything, so the scripts
# stay the single source of operational behaviour and `make start` can never
# drift from `ops/run.sh start`.
#
# Descriptions live in the `## ...` comment on each target and are read back by
# the help target, so a new target documents itself or not at all.

.DEFAULT_GOAL := help

.PHONY: build start stop test logs clean backup restore help

build: ## Build the Docker image
	@ops/run.sh build

start: ## Start the service and wait for healthy status
	@ops/run.sh start

stop: ## Stop the service
	@ops/run.sh stop

test: ## Run unit tests and verify service health
	@ops/run.sh test

logs: ## Tail service logs
	@ops/run.sh logs

clean: ## Remove containers, volumes, and local images
	@ops/run.sh clean

backup: ## Snapshot stored cases to backups/
	@ops/backup.sh

# The $(error) must stay TAB-indented. A tab makes it a recipe line, expanded
# only when `restore` is actually built; indented with spaces it becomes
# makefile text evaluated at parse time, and every target — including help —
# would abort with this message whenever BACKUP_FILE is unset.
restore: ## Restore from backup file: make restore BACKUP_FILE=<path>
ifndef BACKUP_FILE
	$(error BACKUP_FILE is required. Usage: make restore BACKUP_FILE=backups/file.json)
endif
	@ops/restore.sh "$(BACKUP_FILE)"

help: ## Show this help
	@echo "Usage: make <target>"
	@echo ""
	@echo "Targets:"
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  %-8s %s\n", $$1, $$2}'
