# DockSnap

Docker-aware backup scheduler with a REST API. Stops containers before backup, runs `tar` or `borg`, starts them back up, and optionally uploads the artifact.

---

## Table of Contents

- [Quick Start](#quick-start)
  - [Docker Compose](#docker-compose)
  - [Accessing host directories for backup](#accessing-host-directories-for-backup)
- [Configuration](#configuration)
- [Job Schema](#job-schema)
  - [Common fields](#common-fields)
  - [TAR mode](#tar-mode)
  - [Borg mode](#borg-mode)
  - [Upload target](#upload-target)
  - [Scheduling](#scheduling)
- [REST API](#rest-api)
  - [GET /health](#get-health)
  - [GET /jobs](#get-jobs)
  - [POST /jobs/{id}/run](#post-jobsidrun)
  - [GET /runs](#get-runs)
  - [GET /runs/{id}](#get-runsid)
  - [GET /runs/{id}/logs](#get-runsidlogs)
- [Run Object](#run-object)
- [Build](#build)
- [Requirements](#requirements)

---

## Quick Start

### Docker Compose

The recommended way to run DockSnap. Copy the example config, adjust it, and start:

```bash
cp jobs.example.json jobs.json
docker compose up -d
```

The included [`compose.yaml`](compose.yaml) mounts everything that DockSnap needs:

| Mount | Purpose |
|---|---|
| `/var/run/docker.sock` | Lets DockSnap stop/start containers on the host Docker daemon |
| `./jobs.json` → `/config/jobs.json` | Job definitions, read-only |
| `./backups` → `/backups` | TAR artifacts are written here on the host |
| `./secrets/` (commented out) | SSH key + `known_hosts` for Borg-over-SSH jobs |

> **Permission note:** DockSnap runs as a non-root user inside the container. If the Docker socket is restricted to a `docker` group on your host, add the group to the service in `compose.yaml`:
> ```yaml
> group_add:
>   - "999"   # replace with: getent group docker | cut -d: -f3
> ```

---

### Accessing host directories for backup

DockSnap runs inside a container and cannot see the host filesystem directly. To back up host paths (e.g. `/var/nextcloud`), mount them **read-only** into the container and then reference the container-side path in `jobs.json`.

**Step 1 — add the volume in `compose.yaml`:**

```yaml
volumes:
  - /var/run/docker.sock:/var/run/docker.sock
  - ./jobs.json:/config/jobs.json:ro
  - ./backups:/backups
  # host path (left)  →  container path (right)
  - /var/nextcloud:/data/nextcloud:ro
  - /var/lib/postgresql:/data/postgresql:ro
```

**Step 2 — reference the container-side path in `jobs.json`:**

```json
{
  "id": "nextcloud",
  "name": "Nextcloud Backup",
  "mode": "TAR",
  "paths": ["/data/nextcloud"],
  "stopContainers": ["nextcloud"],
  "startContainers": ["nextcloud"],
  "filenamePrefix": "nextcloud",
  "schedule": "0 3 * * *"
}
```

The key rule: `paths` in `jobs.json` are **always container-internal paths**, never host paths. Whatever you mount in `compose.yaml` on the right side of the `:` is what you write in `paths`.

A practical layout for multiple services:

```
Host path                   →  Container path         jobs.json paths entry
/var/nextcloud              →  /data/nextcloud        /data/nextcloud
/var/lib/postgresql/data    →  /data/postgresql       /data/postgresql
/home/user/appdata          →  /data/appdata          /data/appdata
```

For **Borg mode** the paths work exactly the same way — they are passed directly to `borg create` inside the container.

---

### Plain Docker (without Compose)

```bash
docker build -t docksnap .

docker run -d \
  -p 8080:8080 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v $(pwd)/jobs.json:/config/jobs.json:ro \
  -v $(pwd)/backups:/backups \
  -v /var/nextcloud:/data/nextcloud:ro \
  docksnap
```

---

## Configuration

Environment variables read at startup via `AppConfig`:

| Variable     | Default              | Description                               |
|--------------|----------------------|-------------------------------------------|
| `PORT`       | `8080`               | HTTP server port                          |
| `BACKUP_DIR` | `/backups`           | Directory where TAR artifacts are written |
| `JOBS_FILE`  | `/config/jobs.json`  | Path to the job definitions file          |

---

## Job Schema

Jobs are defined in a JSON file at `JOBS_FILE`. The top-level structure is:

```json
{
  "jobs": [ ... ]
}
```

### Common fields

| Field             | Type            | Required | Description                                                                 |
|-------------------|-----------------|----------|-----------------------------------------------------------------------------|
| `id`              | `string`        | yes      | Unique identifier used in API paths and log output                          |
| `name`            | `string`        | yes      | Human-readable display name                                                 |
| `mode`            | `"TAR"` \| `"BORG"` | yes  | Selects the backup engine                                                   |
| `paths`           | `string[]`      | yes      | Absolute paths on the host to include in the backup                         |
| `stopContainers`  | `string[]`      | yes      | Container names to stop **before** the backup starts. Use `[]` for none.   |
| `startContainers` | `string[]`      | yes      | Container names to start **after** the backup finishes. Use `[]` for none. |
| `schedule`        | `string` \| `null` | no    | 5-field cron expression. `null` or omitted = manual-only job.              |
| `borg`            | object \| `null` | no      | Required when `mode` is `BORG`. See [Borg mode](#borg-mode).               |
| `upload`          | object \| `null` | no      | Optional upload step. See [Upload target](#upload-target).                 |

> `stopContainers` and `startContainers` are independent lists. You can stop three containers but only restart one, or stop none and still run the backup.

---

### TAR mode

When `mode` is `"TAR"`, DockSnap shells out to `tar czf` and writes a `.tar.gz` file into `BACKUP_DIR`.

Additional field:

| Field            | Type     | Required | Description                                                                                   |
|------------------|----------|----------|-----------------------------------------------------------------------------------------------|
| `filenamePrefix` | `string` | no       | Prefix for the output filename. Defaults to `id` if omitted. Format: `{prefix}-{timestamp}.tar.gz` |

**Example:**

```json
{
  "id": "postgres-tar",
  "name": "PostgreSQL TAR Backup",
  "mode": "TAR",
  "paths": ["/var/lib/postgresql/data"],
  "stopContainers": ["postgres"],
  "startContainers": ["postgres"],
  "filenamePrefix": "postgres",
  "schedule": "0 2 * * *"
}
```

Produces: `/backups/postgres-20240416-020001.tar.gz`

The `tar` command is built as:
```
tar czf <BACKUP_DIR>/<prefix>-<timestamp>.tar.gz -C / <path1> <path2> ...
```

All paths are made relative to `/` so the archive can be extracted cleanly from any root.

---

### Borg mode

When `mode` is `"BORG"`, DockSnap shells out to `borg create`. The `borg` object is **required**.

| Field           | Type     | Required | Description                                                                       |
|-----------------|----------|----------|-----------------------------------------------------------------------------------|
| `repo`          | `string` | yes      | Borg repository path. Local (e.g. `/mnt/repo`) or remote (`user@host:/path`).    |
| `archivePrefix` | `string` | no       | Prefix for the archive name inside the repo. Defaults to `id`. Format: `{prefix}-{timestamp}` |
| `compression`   | `string` | no       | Borg compression algorithm: `none`, `lz4`, `zstd`, `zlib`, `lzma`. Defaults to Borg's built-in default if omitted. |
| `sshKeyPath`    | `string` | no       | Path to the SSH private key. Sets `BORG_RSH` with `-i <key>` when provided.      |
| `knownHostsPath`| `string` | no       | Path to a `known_hosts` file. Sets `-o UserKnownHostsFile=<path>` in `BORG_RSH`. Requires `sshKeyPath`. |

The effective `borg create` command looks like:
```
borg create --stats --show-rc [--compression <algo>] <repo>::<archive> <path1> <path2> ...
```

When `sshKeyPath` is set, the `BORG_RSH` environment variable is set to:
```
ssh -i <sshKeyPath> -o StrictHostKeyChecking=yes [-o UserKnownHostsFile=<knownHostsPath>]
```

**Example:**

```json
{
  "id": "borg-offsite",
  "name": "Borg Offsite Backup",
  "mode": "BORG",
  "paths": ["/var/lib/postgresql/data", "/var/data/uploads"],
  "stopContainers": ["postgres", "app"],
  "startContainers": ["postgres", "app"],
  "borg": {
    "repo": "borg@backup.example.com:/mnt/repo",
    "archivePrefix": "myserver",
    "compression": "lz4",
    "sshKeyPath": "/secrets/id_rsa",
    "knownHostsPath": "/secrets/known_hosts"
  },
  "schedule": "30 3 * * 0"
}
```

> **Note:** The `artifact` field in the resulting [Run object](#run-object) contains the full `repo::archive` identifier. The `bytes` field is always `0` for Borg jobs (size reporting is not yet implemented).

---

### Upload target

The `upload` field is reserved for a future upload step and is currently a no-op. It is safe to set to `null` or omit entirely.

```json
"upload": {
  "type": "noop",
  "uri": ""
}
```

| Field  | Type     | Description                                        |
|--------|----------|----------------------------------------------------|
| `type` | `string` | Upload backend type. Only `noop` exists currently. |
| `uri`  | `string` | Destination URI. Unused until a backend is implemented. |

---

### Scheduling

The `schedule` field accepts a standard **5-field Unix cron expression**:

```
┌─── minute       (0–59)
│ ┌─── hour         (0–23)
│ │ ┌─── day of month (1–31)
│ │ │ ┌─── month        (1–12)
│ │ │ │ ┌─── day of week  (0–7, 0 and 7 = Sunday)
│ │ │ │ │
* * * * *
```

Common examples:

| Expression     | Meaning                          |
|----------------|----------------------------------|
| `0 2 * * *`    | Every day at 02:00               |
| `30 3 * * 0`   | Every Sunday at 03:30            |
| `0 */6 * * *`  | Every 6 hours                    |
| `0 4 1 * *`    | First day of every month at 04:00|

Set `schedule` to `null` or omit it for **manual-only jobs** (trigger via `POST /jobs/{id}/run`).

> **Scheduler precision:** The scheduler polls every **60 seconds**. A job can fire up to 60 seconds later than its exact cron minute. It does not fire immediately on startup — the first tick is after 60 seconds.

---

## REST API

The API is served on `PORT` (default `8080`). All endpoints return and accept JSON. [CORS](https://developer.mozilla.org/en-US/docs/Web/HTTP/CORS) is enabled for all origins.

---

### GET /health

Health check. Always returns `200` when the server is running.

**Response `200`:**
```json
{ "status": "ok" }
```

---

### GET /jobs

Returns all jobs currently loaded from `JOBS_FILE`.

**Response `200`:** Array of job objects (full schema as defined in `jobs.json`).

```bash
curl http://localhost:8080/jobs
```

---

### POST /jobs/{id}/run

Triggers a job immediately, regardless of its schedule. The job runs asynchronously in the background.

**Path parameter:**

| Parameter | Description                    |
|-----------|--------------------------------|
| `id`      | The `id` field of the job      |

**Response `202`:** The newly created [Run object](#run-object) with `status: "RUNNING"`.

**Response `404`:**
```json
{ "error": "Job not found: <id>" }
```

```bash
curl -X POST http://localhost:8080/jobs/postgres-tar/run
```

---

### GET /runs

Returns the last 50 runs, newest first.

**Response `200`:** Array of [Run objects](#run-object).

```bash
curl http://localhost:8080/runs
```

---

### GET /runs/{id}

Returns a single run by its UUID.

**Path parameter:**

| Parameter | Description     |
|-----------|-----------------|
| `id`      | UUID of the run |

**Response `200`:** A single [Run object](#run-object).

**Response `404`:**
```json
{ "error": "Run not found: <id>" }
```

```bash
curl http://localhost:8080/runs/3f1a2b4c-...
```

---

### GET /runs/{id}/logs

Returns the last N log lines captured during the run. The buffer holds up to **2000 lines** per run.

**Path parameter:**

| Parameter | Description     |
|-----------|-----------------|
| `id`      | UUID of the run |

**Query parameter:**

| Parameter | Default | Description                        |
|-----------|---------|------------------------------------|
| `last`    | `100`   | Number of most recent lines to return |

**Response `200`:**
```json
{
  "runId": "3f1a2b4c-...",
  "lines": [
    "TAR: tar czf /backups/postgres-20240416-020001.tar.gz -C / var/lib/postgresql/data",
    "[tar] ...",
    "Backup complete."
  ]
}
```

```bash
# Last 100 lines (default)
curl http://localhost:8080/runs/3f1a2b4c-.../logs

# Last 500 lines
curl "http://localhost:8080/runs/3f1a2b4c-.../logs?last=500"
```

---

## Run Object

All run-related endpoints return objects of this shape:

| Field            | Type                               | Description                                                              |
|------------------|------------------------------------|--------------------------------------------------------------------------|
| `id`             | `string` (UUID)                    | Unique run identifier                                                    |
| `jobId`          | `string`                           | ID of the job that was executed                                          |
| `jobName`        | `string`                           | Display name of the job at execution time                                |
| `mode`           | `"TAR"` \| `"BORG"`               | Backup mode used                                                         |
| `stopContainers` | `boolean`                          | Whether any containers were stopped for this run                         |
| `status`         | `"RUNNING"` \| `"SUCCESS"` \| `"FAILED"` | Current run state                                               |
| `startedAt`      | `string` (ISO-8601)                | Timestamp when the run was created                                       |
| `finishedAt`     | `string` (ISO-8601) \| `null`      | Timestamp when the run completed. `null` while still running.            |
| `artifact`       | `string` \| `null`                 | TAR: absolute path to the `.tar.gz` file. BORG: `repo::archive` string. |
| `bytes`          | `number` \| `null`                 | Size of the artifact in bytes. Always `0` for BORG jobs.                |
| `error`          | `string` \| `null`                 | Error message if `status` is `"FAILED"`. `null` otherwise.              |

**Example (successful TAR run):**

```json
{
  "id": "3f1a2b4c-dead-beef-1234-567890abcdef",
  "jobId": "postgres-tar",
  "jobName": "PostgreSQL TAR Backup",
  "mode": "TAR",
  "stopContainers": true,
  "status": "SUCCESS",
  "startedAt": "2024-04-16T02:00:01Z",
  "finishedAt": "2024-04-16T02:00:47Z",
  "artifact": "/backups/postgres-20240416-020001.tar.gz",
  "bytes": 104857600,
  "error": null
}
```

**Example (failed run):**

```json
{
  "id": "aabbccdd-...",
  "jobId": "borg-offsite",
  "jobName": "Borg Offsite Backup",
  "mode": "BORG",
  "stopContainers": true,
  "status": "FAILED",
  "startedAt": "2024-04-16T03:30:00Z",
  "finishedAt": "2024-04-16T03:30:03Z",
  "artifact": null,
  "bytes": null,
  "error": "BORG job requires borg.repo"
}
```

---

## Build

```bash
mvn clean package -DskipTests
java -jar target/DockSnap-1.0-SNAPSHOT.jar
```

---

## Requirements

| Requirement | Details |
|-------------|---------|
| Docker socket | Accessible at `/var/run/docker.sock` or via `DOCKER_HOST` |
| `tar` in PATH | Required for `TAR` mode jobs |
| `borg` in PATH | Required for `BORG` mode jobs |