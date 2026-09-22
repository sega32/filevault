# FileVault — Quick Start

Full documentation: [README.md](README.md)

## 1. Build

```bash
./mvnw package
```

Requires JDK 21+. Produces `backend/target/filevault-backend-1.0.0.jar` and
`cli/target/filevault-cli.jar`.

## 2. Start the server

Authentication is on by default, so give it a key — the server refuses to start without one:

```bash
export FILEVAULT_API_KEY=$(openssl rand -hex 32)
APP_SECURITY_API_KEYS_0="$FILEVAULT_API_KEY" java -jar backend/target/filevault-backend-1.0.0.jar
```

Listening on `http://localhost:8080/api`.

## 3. Use the CLI

The CLI picks up `FILEVAULT_API_KEY` from the environment.

```bash
java -jar cli/target/filevault-cli.jar health
java -jar cli/target/filevault-cli.jar upload ./report.pdf
java -jar cli/target/filevault-cli.jar list
java -jar cli/target/filevault-cli.jar info <file-id>
java -jar cli/target/filevault-cli.jar download <file-id> ./restored.pdf
java -jar cli/target/filevault-cli.jar delete <file-id>
```

Exit codes: `0` success, `1` operation failed, `2` usage error.

## What happens to your file

**Upload** — SHA-256 digested → gzipped → split into 4 MB chunks → optionally AES-256-GCM
encrypted → each chunk hashed and written to `storage/<file-id>/` → metadata committed to SQLite.

**Download** — each chunk read, size- and hash-checked, decrypted → reassembled → decompressed →
streamed back. The CLI re-checks the whole-file SHA-256 and the `Content-Length` before declaring
success.

Memory use is bounded by the chunk size, not the file size, on both ends.

## Expected compression

| Content | Typical saving |
|---|---|
| Text, logs, JSON | 60–99% |
| Source code | 40–60% |
| Office documents, PDFs | 5–30% |
| JPEG/PNG, MP4, ZIP | ~0% (already compressed; gzip may add a fraction of a percent) |

Compression ratio is reported per file after upload — measured, not estimated.

## Optional: encryption at rest

```bash
export FILEVAULT_ENCRYPTION_KEY=$(openssl rand -base64 32)
APP_SECURITY_API_KEYS_0="$FILEVAULT_API_KEY" \
APP_ENCRYPTION_ENABLED=true \
APP_ENCRYPTION_KEY="$FILEVAULT_ENCRYPTION_KEY" \
java -jar backend/target/filevault-backend-1.0.0.jar
```

Lose the key and the chunks are unrecoverable. Only chunks written while encryption is on are
encrypted; there is no re-encryption of existing data.

## Docker

```bash
export FILEVAULT_API_KEY=$(openssl rand -hex 32)
docker compose up --build
```

## Troubleshooting

| Symptom | Cause |
|---|---|
| `No API keys configured` at startup | Set `APP_SECURITY_API_KEYS_0`, or `APP_SECURITY_ENABLED=false` for local development only |
| `HTTP 401` from the CLI | Missing or wrong `FILEVAULT_API_KEY` |
| `Cannot connect to the FileVault server` | Backend not running, or wrong `--server` URL |
| `Address already in use` | Something else holds port 8080; set `SERVER_PORT` |
| `Download ... ended early` | A stored chunk failed verification — check the server log for `Integrity failure` |
