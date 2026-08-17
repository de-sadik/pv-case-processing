# PV Case Processing — Operations Runbook

On-call reference for building, verifying, and recovering this service.
For the API itself — endpoints, payloads, merge semantics — see
[`backend/README.md`](backend/README.md).

---

## Read this first

Three facts that change how you should react to an incident:

1. **All data is in memory.** There is no database. Restarting the container
   discards every case and every reviewer query. Only `PV-2026-0451` comes back,
   because it is re-seeded from `case_v1.json` on the classpath at startup.
2. **`ops/restore.sh` does not recover lost data.** It replays a backup through
   the follow-up *merge* endpoint. A case that is no longer in the store returns
   404 and cannot be recreated. See [Backup and restore](#3-backup-and-restore).
3. **A `500` may be a client error in disguise.** The catch-all exception
   handler claims some errors Spring would otherwise map to `400`/`404`/`405`.
   See [Known traps](#known-traps).

### First 60 seconds

```bash
cd /path/to/pv-case-processing        # every command below assumes repo root
docker-compose ps                     # is the container up?
curl -s http://localhost:8080/health  # is it answering?
make logs                             # what is it saying? (Ctrl-C to exit)
```

---

## 1. Build and deploy

```bash
make build && make start
```

`make build` ends with:

```
 Image pv-case-processing-pv-cases Built
[2026-08-17 09:17:36] Build complete
```

`make start` brings the container up and polls `/health` every 3s, up to 10
attempts:

```
 Container pv-cases Started
[2026-08-17 09:17:37] Waiting for http://localhost:8080/health (up to 30s)...
[2026-08-17 09:17:37]   attempt 1/10 - not ready yet
[2026-08-17 09:17:40] Service is UP
```

`attempt 1/10 - not ready yet` on the first poll is normal — the JVM has not
finished booting 3 seconds in. Anything past attempt 3 is worth watching.

### Verify the image built correctly

```bash
docker images | grep pv-case
docker run --rm --entrypoint ls   pv-case-processing-pv-cases -l /app/app.jar
docker run --rm --entrypoint java pv-case-processing-pv-cases -version
docker run --rm --entrypoint id   pv-case-processing-pv-cases
```

Expected:

```
pv-case-processing-pv-cases:latest  573MB
-rw-r--r-- 1 appuser appgroup 21562896 /app/app.jar
openjdk version "17.0.19" 2026-04-21
uid=999(appuser) gid=999(appgroup) groups=999(appgroup)
```

Four things to confirm: the jar exists, it is ~21 MB (a few hundred KB means the
Spring Boot repackage did not run and dependencies are missing), the runtime is
Java 17, and the process user is `appuser` — **not** `uid=0(root)`.

---

## 2. Verify the service is healthy

```bash
curl -s http://localhost:8080/health
```

```json
{"status":"UP","cases_loaded":1,"queries_count":0}
```

`cases_loaded: 0` means the service is running but **the bootstrap did not
load** — treat it as an outage, not a healthy service. `/health` returns `200`
either way, so the counts are the real signal.

```bash
docker-compose ps
```

```
NAME       IMAGE                         COMMAND               STATUS                            PORTS
pv-cases   pv-case-processing-pv-cases   "java -jar app.jar"   Up 3 seconds (health: starting)   0.0.0.0:8080->8080/tcp
```

`(health: starting)` for the first ~40s is expected — that is the healthcheck's
`start_period`, not a fault. It becomes `(healthy)` afterwards. `make start` can
report "Service is UP" while Docker still says `starting`; the endpoint answers
before Docker's first probe lands. Only `(unhealthy)` is a problem.

### What healthy startup logs look like

```
Starting CasesApplication v0.0.1-SNAPSHOT using Java 17.0.19 with PID 1 (/app/app.jar started by appuser in /app)
Tomcat initialized with port 8080 (http)
Root WebApplicationContext: initialization completed in 343 ms
Tomcat started on port 8080 (http) with context path '/'
Started CasesApplication in 0.674 seconds (process running for 0.89)
Loaded case PV-2026-0451 with 4 sections
```

The last line is the one that matters. **If `Loaded case … with 4 sections` is
absent, the service has no data** even though it is serving traffic. Startup
should take well under 2 seconds.

`Initializing Spring DispatcherServlet` appears on the *first request*, not at
startup. Seeing it later in the log is normal.

---

## 3. Backup and restore

### Back up

```bash
./ops/backup.sh          # or: make backup
```

Writes `backups/backup_<YYYYMMDD>_<HHMMSS>.json` — a JSON array of full case
documents — at the repo root. It is non-interactive and safe for cron:

```cron
0 * * * * cd /path/to/pv-case-processing && ./ops/backup.sh >> /var/log/pv-backup.log 2>&1
```

Hourly is a reasonable default given the data lives in memory and dies with the
process. Override the destination with `BACKUP_DIR=/mnt/backups ./ops/backup.sh`
and the target with `BASE_URL=http://host:8080`.

The script aborts without writing anything if the service is unreachable or any
case fetch fails, so a file that exists is a complete file.

**It captures cases only.** Reviewer queries are not in the snapshot and cannot
be restored from it.

### Restore — dry run first, always

```bash
./ops/restore.sh backups/backup_20260817_085458.json --dry-run
```

Prints the target URL and payload for each case, makes no requests, and does not
require a running service. When it looks right:

```bash
./ops/restore.sh backups/backup_20260817_085458.json
# or: make restore BACKUP_FILE=backups/backup_20260817_085458.json
```

Success looks like:

```
  OK PV-2026-0451 (HTTP 200) {"unchanged":14,"overridden":0,"new":0,"retained":0}
Restored 1/1 case(s), 0 failure(s)
```

`overridden: 0, new: 0` means the live case already matched the backup.

### What restore actually does — read before relying on it

Restore replays each case through `POST /cases/{caseId}/follow-ups`, the only
write path the API exposes. Consequences:

| Behaviour | Detail |
|---|---|
| Field values converge | Safe to run repeatedly; values end up matching the backup |
| Version increments every run | Three restores leave the case claiming version 4 |
| `extracted_at` is rewritten | It becomes the merge time, not the extraction time |
| Newer fields are kept, not removed | It merges forward; it cannot roll a case back |
| A missing case returns 404 | **It cannot recreate data that is gone** |

So: restore is useful for re-applying known-good values to a case that still
exists. It is **not** disaster recovery. If the container restarted and took the
data with it, only `PV-2026-0451` returns, via the classpath seed.

---

## 4. Debugging a failed startup

```bash
make logs        # docker-compose logs -f — Ctrl-C to exit
```

### Port 8080 already in use

```
Error response from daemon: ports are not available: exposing port TCP 0.0.0.0:8080 ->
127.0.0.1:0: listen tcp 0.0.0.0:8080: bind: address already in use
make: *** [start] Error 1
```

Find and clear the squatter:

```bash
lsof -nP -iTCP:8080 -sTCP:LISTEN
kill <pid>
```

Frequently it is a stray `mvn spring-boot:run` or `java -jar` from local testing,
or a previous container that did not come down. `make stop` then `make start`.

### Docker not running

```
[ERROR] Docker is not running. Start Docker Desktop first.
```

Every `ops/run.sh` subcommand except `test` checks this first and exits 1 before
doing anything. Start Docker Desktop and retry.

### case_v1.json missing from resources

The service refuses to start rather than come up empty:

```
[ERROR] /case_v1.json not found on the classpath; the case store would start empty
```

The container will exit. Confirm the file is inside the jar:

```bash
unzip -p backend/target/cases-0.0.1-SNAPSHOT.jar BOOT-INF/classes/case_v1.json | head -3
```

Should print the opening of the JSON. If it is missing, the file is absent from
`backend/src/main/resources/` — restore it and rebuild. Two related failures use
the same startup path: a `case_id` in the file that isn't `PV-2026-0451`, and
malformed JSON. Both abort startup with a specific message.

### Verify the jar was built correctly

```bash
ls -l backend/target/*.jar
unzip -l backend/target/cases-0.0.1-SNAPSHOT.jar | grep -c BOOT-INF
```

Expect ~21 MB and a large `BOOT-INF` count. A jar of a few hundred KB is an
un-repackaged jar — it will fail at runtime with `no main manifest attribute`.
Rebuild with `mvn package -DskipTests -f backend/pom.xml`.

### Fallback: run without Docker

If Docker itself is the problem and you need the service up now:

```bash
mvn package -DskipTests -f backend/pom.xml
java -jar backend/target/cases-0.0.1-SNAPSHOT.jar
```

Same application, same port, same seeded case.

---

## 5. Requests are failing — what to check first

Work down this list in order.

**1. Is the container running?**

```bash
docker-compose ps
```

No row, `Exited`, or `Restarting` → go to
[Debugging a failed startup](#4-debugging-a-failed-startup). `Restarting` in a
loop usually means the app aborts at boot; `restart: unless-stopped` will retry
it forever, so read the logs rather than the status.

**2. Is `/health` returning UP?**

```bash
curl -s -w '\n%{http_code}\n' http://localhost:8080/health
```

No response → the container is not listening. `200` with `cases_loaded: 0` →
running but empty; see [Backup and restore](#3-backup-and-restore).

**3. Any stack traces in the logs?**

```bash
make logs
```

Every unhandled exception is logged in full at `ERROR` before the client gets
`{"error":"Internal server error"}`. The response body is deliberately bare, so
**the log is the only record of the cause**.

**4. Is the case bootstrapped?**

```bash
curl -s http://localhost:8080/cases/PV-2026-0451 | jq -c '{case_id, version, sections: (.sections|keys)}'
```

```json
{"case_id":"PV-2026-0451","version":1,"sections":["adverse_event","patient","reporter","suspect_drug"]}
```

`404` here while `/health` says `UP` means the store is empty — the service came
up without its seed, or the case was never restored after a restart.

A `version` above 1 is not a fault: every follow-up merge and every restore
increments it.

---

## Known traps

Things that will mislead you at 2am.

| Symptom | Reality |
|---|---|
| `500 {"error":"Internal server error"}` on `GET /queries` | Missing the required `caseId` parameter. Should be a `400`; the catch-all handler claims it first. Add `?caseId=PV-2026-0451`. |
| `500` on a malformed JSON body, or on a wrong HTTP verb | Same cause — should be `400` and `405`. Check the request before the service. |
| `source_document` shows only the newest file after a merge | Expected. `MergedCase` tracks a list, `CaseDocument` holds one string, so flattening keeps the most recent. Earlier provenance is lost. |
| `extracted_at` looks like today | After a merge or restore it holds the merge timestamp, not the extraction time. |
| Two backups in the same second | Filenames have 1-second resolution and `mv` overwrites — the second silently replaces the first. Not a risk on a cron schedule. |
| `the attribute 'version' is obsolete` on every compose command | Harmless warning from `docker-compose.yml`. Ignore it. |
| Setting `JAVA_OPTS` has no effect | Use `JAVA_TOOL_OPTIONS` — compose already sets it to `-Xmx512m`. The exec-form `ENTRYPOINT` runs no shell, so a `$JAVA_OPTS` reference would never expand; the JVM reads `JAVA_TOOL_OPTIONS` on its own. Confirm with `make logs \| grep "Picked up"`. |
| `./mvnw` fails | `backend/.mvn/wrapper/` is missing from the repo. Use system `mvn`. |

---

## Command reference

```
make help                                    List all targets
make build                                   Build the Docker image
make start                                   Start and wait for health
make stop                                    Stop the service
make logs                                    Tail logs (Ctrl-C to exit)
make test                                    Unit tests + health check
make clean                                   Remove containers, volumes, local images
make backup                                  Snapshot cases to backups/
make restore BACKUP_FILE=backups/f.json      Replay a backup
```

`make test` runs the 29-test suite against the working tree and then probes
`/health`; it fails if the service is not running. It does not require Docker.

All `make` targets exit `2` on failure (make's convention), while the `ops/`
scripts exit `1` when run directly.
