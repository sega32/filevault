# FileVault — Build, Run and Deploy

Reference documentation: [README.md](README.md) · Fastest path: [QUICK_START.md](QUICK_START.md)

## Prerequisites

| | |
|---|---|
| JDK | 21 or newer (Spring Boot 3.4 requires 17+) |
| Maven | 3.9+, or use the bundled `./mvnw` wrapper |
| Docker | optional, for the container build |

Check your toolchain:

```bash
java -version && ./mvnw -v
```

## Build

```bash
./mvnw clean package
```

| Artifact | Description |
|---|---|
| `backend/target/filevault-backend-1.0.0.jar` | Executable Spring Boot jar |
| `cli/target/filevault-cli.jar` | Shaded CLI, all dependencies included |

Build one module only:

```bash
./mvnw -pl backend package
./mvnw -pl cli package
```

Skip tests (not recommended):

```bash
./mvnw package -DskipTests
```

## Run the backend

```bash
export FILEVAULT_API_KEY=$(openssl rand -hex 32)
APP_SECURITY_API_KEYS_0="$FILEVAULT_API_KEY" java -jar backend/target/filevault-backend-1.0.0.jar
```

Startup creates `./storage` and `./filevault.db` if absent. Confirm it is up:

```bash
curl http://localhost:8080/api/files/health
```

### Common overrides

Any property can be passed as an environment variable or a `--flag`:

```bash
SERVER_PORT=9090 \
APP_STORAGE_PATH=/var/lib/filevault/storage \
APP_CHUNK_SIZE=8388608 \
APP_SECURITY_API_KEYS_0="$FILEVAULT_API_KEY" \
java -jar backend/target/filevault-backend-1.0.0.jar
```

Note that shells expand `[` and `]`, so quote index syntax if you use the flag form:

```bash
java -jar backend/target/filevault-backend-1.0.0.jar '--app.security.api-keys[0]=your-key'
```

### Development mode

Authentication can be disabled for local work. It logs a loud warning, and should never be used
anywhere reachable by anyone else:

```bash
APP_SECURITY_ENABLED=false java -jar backend/target/filevault-backend-1.0.0.jar
```

### JVM sizing

Memory use is bounded by chunk size, not file size. A 400 MB upload split across 101 encrypted
chunks was verified to complete with `-Xmx192m`. Defaults are fine for most deployments:

```bash
java -Xmx512m -XX:+ExitOnOutOfMemoryError -jar backend/target/filevault-backend-1.0.0.jar
```

## Use the CLI

```bash
export FILEVAULT_API_KEY=...              # same key the server was started with
export FILEVAULT_SERVER=http://localhost:8080/api   # optional; this is the default

java -jar cli/target/filevault-cli.jar upload ./report.pdf
java -jar cli/target/filevault-cli.jar list
java -jar cli/target/filevault-cli.jar download <file-id> ./restored.pdf
```

Handy as a shell function:

```bash
filevault() { java -jar /path/to/filevault-cli.jar "$@"; }
```

## Tests

```bash
./mvnw verify                                   # everything
./mvnw -pl backend test                         # backend only
./mvnw -pl backend test -Dtest=FileVaultIntegrationTest
```

Reports land in `*/target/surefire-reports/`.

## Docker

```bash
docker build -t filevault-backend:1.0.0 .

docker run -d --name filevault -p 8080:8080 \
  -e APP_SECURITY_API_KEYS_0="$FILEVAULT_API_KEY" \
  -v filevault-data:/data \
  filevault-backend:1.0.0
```

Or with Compose, which wires up the volume, healthcheck and hardening:

```bash
export FILEVAULT_API_KEY=$(openssl rand -hex 32)
docker compose up --build
```

The image runs as an unprivileged user with a read-only root filesystem; `/data` holds both the
chunk directory and the SQLite database.

## Deploying

1. **Terminate TLS upstream.** The service speaks plain HTTP; put nginx, Caddy or a load balancer
   in front of it. Never expose it directly.
2. **Set a strong API key** (`openssl rand -hex 32`) and deliver it as a secret, not in an image
   or a compose file committed to git.
3. **Enable encryption at rest** if the data warrants it, and store the key in a secret manager.
   Losing it means losing the data.
4. **Back up `storage/` and `filevault.db` together.** Either one alone is useless. Snapshot with
   the service stopped, or copy the database using `sqlite3 .backup` to get a consistent WAL read.
5. **Scrape `/api/actuator/prometheus`** and alert on any non-zero `filevault.integrity.failures`.
6. **Watch disk.** `/api/files/stats` reports free space on the storage volume.

## CI

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs `./mvnw verify` on JDK 21, publishes
test reports and jars, and builds the container image on every push and pull request.

## Troubleshooting

| Message | Meaning and fix |
|---|---|
| `No API keys configured` | Auth is on with no key. Set `APP_SECURITY_API_KEYS_0`. |
| `app.encryption.enabled=true requires app.encryption.key` | Provide a base64 32-byte key. |
| `app.encryption.key must decode to 32 bytes` | Key is the wrong length; regenerate with `openssl rand -base64 32`. |
| `Cannot create storage directory` | The path is not writable by the service user. |
| `Address already in use` | Port taken; set `SERVER_PORT`. |
| `Integrity failure while streaming <id>` | A stored chunk is damaged. Restore that file from backup. |
| `database is locked` | Another process holds the SQLite file. Run one server per database. |
