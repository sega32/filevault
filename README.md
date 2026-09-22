# FileVault — compressed, chunked, integrity-checked file storage

A file storage service built with **Spring Boot 3.4** and a **Java CLI** client. Uploads are gzip
compressed, split into fixed-size chunks, optionally encrypted with AES-256-GCM, and written to
disk. A SHA-256 digest is recorded per chunk and verified on every read, and metadata lives in
SQLite via Spring Data JPA.

```
┌─────────────┐      HTTPS/REST      ┌──────────────────────────────┐
│  Java CLI   │◄────────────────────►│  Spring Boot backend         │
│  client     │   X-API-Key header   │  (API key auth, stateless)   │
└─────────────┘                      └──────────────┬───────────────┘
                                                    │
                              compress → chunk → [encrypt] → hash
                                                    │
                                     ┌──────────────┴──────────────┐
                                     ▼                             ▼
                              ┌─────────────┐             ┌────────────────┐
                              │   SQLite    │             │  Disk storage  │
                              │  metadata   │             │  4 MB chunks   │
                              └─────────────┘             └────────────────┘
```

## What it does

| | |
|---|---|
| **Compression** | gzip level 6, applied as a stream — never buffers the whole file |
| **Chunking** | compressed stream split into 4 MB chunks (configurable) |
| **Integrity** | SHA-256 per chunk, verified before any byte is served; whole-file digest checked by the client |
| **Encryption at rest** | optional AES-256-GCM, fresh IV per chunk, authenticated |
| **Authentication** | API key, constant-time comparison, on by default — the server refuses to start unauthenticated by accident |
| **Observability** | Actuator health/metrics/Prometheus, per-request correlation ids, counters for uploads, downloads and integrity failures |
| **Errors** | RFC 9457 (`application/problem+json`) responses |

## Requirements

- **JDK 21+** (Spring Boot 3.x requires 17+; this project targets 21)
- **Maven** — or use the bundled wrapper, `./mvnw`
- Docker, optionally, for containerised deployment

## Build

```bash
./mvnw package
```

Produces:

- `backend/target/filevault-backend-1.0.0.jar` — executable Spring Boot jar
- `cli/target/filevault-cli.jar` — shaded CLI jar

## Run

The server will not start with authentication enabled and no API key configured. Generate one:

```bash
export FILEVAULT_API_KEY=$(openssl rand -hex 32)
```

Start the backend:

```bash
APP_SECURITY_API_KEYS_0="$FILEVAULT_API_KEY" java -jar backend/target/filevault-backend-1.0.0.jar
```

It listens on `http://localhost:8080/api`. Then, in another terminal:

```bash
java -jar cli/target/filevault-cli.jar upload ./report.pdf
java -jar cli/target/filevault-cli.jar list
java -jar cli/target/filevault-cli.jar download <file-id> ./restored.pdf
java -jar cli/target/filevault-cli.jar info <file-id>
java -jar cli/target/filevault-cli.jar delete <file-id>
```

The CLI reads `FILEVAULT_API_KEY` and `FILEVAULT_SERVER` from the environment; both can be
overridden with `--api-key` and `--server`.

### With Docker

```bash
export FILEVAULT_API_KEY=$(openssl rand -hex 32)
docker compose up --build
```

Chunks and the database live on the `filevault-data` volume. The container runs as an
unprivileged user with a read-only root filesystem.

## Configuration

Every property can be set as an environment variable using Spring's relaxed binding
(`app.storage.path` → `APP_STORAGE_PATH`).

| Property | Default | Meaning |
|---|---|---|
| `server.port` | `8080` | HTTP port |
| `server.servlet.context-path` | `/api` | Base path |
| `app.storage.path` | `./storage` | Root directory for chunk files |
| `app.chunk.size` | `4194304` | Chunk size in bytes (4 MB) |
| `app.security.enabled` | `true` | API-key authentication |
| `app.security.api-keys[n]` | — | Accepted keys; **required** when auth is enabled |
| `app.security.header` | `X-API-Key` | Header carrying the key |
| `app.security.cors-origins[n]` | empty | Allowed CORS origins; `*` is rejected while auth is on |
| `app.encryption.enabled` | `false` | AES-256-GCM at rest |
| `app.encryption.key` | — | base64-encoded 32 bytes; **required** when encryption is on |
| `spring.servlet.multipart.max-file-size` | `5GB` | Upload limit |

Generate an encryption key with `openssl rand -base64 32`. Enabling encryption without a valid key
is a startup failure, never a silent fallback to plaintext.

> Encryption applies to chunks written *after* it is enabled. There is no key rotation or
> re-encryption of existing chunks; turning it on for a vault that already holds plaintext chunks
> leaves those chunks readable.

## API

All paths are relative to `http://localhost:8080/api`. Every endpoint except `/files/health`
requires the `X-API-Key` header when authentication is enabled.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/files/upload` | multipart upload, field name `file` → `201` + metadata |
| `GET` | `/files/{id}/download` | reassembled, verified, decompressed bytes |
| `GET` | `/files/{id}` | metadata for one file |
| `GET` | `/files` | all files, newest first |
| `DELETE` | `/files/{id}` | delete metadata and chunks |
| `GET` | `/files/stats` | vault totals |
| `GET` | `/files/health` | unauthenticated liveness probe |
| `GET` | `/actuator/health`, `/actuator/metrics`, `/actuator/prometheus` | operations |

Metadata response:

```json
{
  "fileId": "ccbc9b5b-84a2-48b1-b763-d458de9f6850",
  "filename": "report.pdf",
  "contentType": "application/pdf",
  "originalSize": 7712104,
  "compressedSize": 4218043,
  "compressionRatio": 45.31,
  "chunkCount": 2,
  "sha256": "c3424ac52433a4c92fff8682f0d9cbbed00637150e6bf59735b1026da30403c3",
  "createdAt": "2026-07-27T15:17:04.029844Z"
}
```

Downloads carry `X-Content-SHA256` (digest of the original bytes) and an accurate `Content-Length`.
The CLI checks both, so a truncated or altered transfer is caught client-side as well.

Errors are problem documents:

```json
{
  "type": "https://filevault.dev/problems/file-not-found",
  "title": "File not found",
  "status": 404,
  "detail": "No file with id 5f2c...",
  "timestamp": "2026-07-27T15:20:11.402Z",
  "path": "/api/files/5f2c..."
}
```

## How storage works

**Upload.** The request stream is digested (SHA-256 of the original bytes), gzipped, and fed to a
chunk writer that fills one 4 MB buffer at a time. Each full buffer is optionally encrypted,
hashed, and written to `storage/<file-id>/chunk_00000.bin`. Metadata is committed to SQLite only
after every chunk is safely on disk; if anything fails, the partial chunk directory is removed, so
a failed upload never leaves orphans.

**Download.** Chunks are read in order. Each one is read whole, checked against its recorded size
and SHA-256, and decrypted *before* any of its bytes are emitted, then piped through gzip
decompression to the response.

Memory is bounded by the chunk size, not the file size, on both ends. Verified: an 818 MB file and
a 400 MB incompressible file (101 chunks, encryption on) both round-tripped byte-identically with
the server capped at `-Xmx192m` and the CLI at `-Xmx128m`.

Chunk I/O deliberately happens outside the database transaction. SQLite permits a single writer;
holding a write transaction open for a multi-gigabyte upload would serialise the whole server
behind one client.

## Integrity guarantees, and one honest limit

Corruption is always detected — never served as if it were good data. But *when* it is reported
depends on which chunk is damaged:

- Damage in the **first** chunk is caught before the response is committed: the client gets a clean
  `500` problem document.
- Damage in a **later** chunk is caught partway through the stream, after `200 OK` and earlier bytes
  have already gone out. HTTP has no way to retract a committed status, so the server aborts the
  transfer and logs `Integrity failure while streaming <file-id>` at ERROR, and increments the
  `filevault.integrity.failures` counter. The client sees a short read, compares it against
  `Content-Length`, and fails loudly rather than keeping the partial file silently.

Verifying every chunk before streaming would close this gap at the cost of reading each file twice.
That trade was resolved in favour of single-pass streaming; the mitigation is that both ends fail
loudly and the event is alertable.

## Security

Implemented:

- API-key authentication, constant-time comparison, stateless (no sessions, no cookies, CSRF off by design)
- Secure-by-default startup: enabled auth with no keys, or enabled encryption with no key, is a fatal error
- Optional AES-256-GCM encryption at rest, authenticated, fresh IV per chunk
- Path-traversal defence: file ids must be UUIDs and every resolved path is confirmed to stay under the storage root
- Uploaded filenames reduced to a bare basename with control characters stripped
- Explicit CORS allowlist; `*` rejected while auth is on
- Server errors reported without internals; stack traces and messages never leaked to clients

Not implemented — deliberate scope boundaries, and what you would add for a hostile environment:

- **No TLS termination.** Run behind a reverse proxy or load balancer that terminates HTTPS.
- **No per-user identity or authorization.** Any valid key can read, write and delete any file.
  Multi-tenancy needs an owner column and per-request identity.
- **No rate limiting or quota enforcement.**
- **No audit log** of who did what.
- **No key rotation** for encryption or API keys.
- **No replication or backup.** Back up both `storage/` and `filevault.db` together — one without
  the other is useless.

## Operations

- **Health:** `/api/files/health` (open) and `/api/actuator/health` (detailed, authenticated).
- **Metrics:** `/api/actuator/prometheus`. Custom meters: `filevault.uploads`,
  `filevault.downloads`, `filevault.integrity.failures`, `filevault.upload.duration`.
- **Logs:** every line carries a request id, echoed to clients as `X-Request-Id`; supply your own
  inbound to trace a request end to end.
- **Alert on** `filevault.integrity.failures` — any non-zero value means stored data is damaged.
- **Shutdown** is graceful, with a 30 s drain.

### Database

Schema lives in [`backend/src/main/resources/db/schema.sql`](backend/src/main/resources/db/schema.sql)
and is applied at startup; Hibernate's `ddl-auto` is `none`, so the database shape is reviewable in
version control rather than inferred from entities. SQLite runs in WAL mode, allowing concurrent
readers alongside the single writer.

```sql
files  (file_id PK, filename, content_type, original_size, compressed_size,
        original_sha256, chunk_count, created_at)
chunks (file_id FK, chunk_index, chunk_hash, chunk_size, PK (file_id, chunk_index))
```

SQLite is a deliberate fit for a single-node vault. A multi-node deployment needs both a networked
database and shared or replicated chunk storage — see *Scaling* below.

## Testing

```bash
./mvnw verify
```

70 tests: gzip round trips including incompressible and empty input; chunk boundary handling;
corruption, truncation, missing-chunk and path-traversal detection; encryption round trip, IV
uniqueness and tamper rejection; filename sanitisation; and full-stack HTTP tests over real SQLite
and a real filesystem covering upload, byte-for-byte download across many chunks, listing, delete,
error responses and API-key enforcement.

Integration tests use an 8 KB chunk size so ordinary payloads span many chunks — the multi-chunk
path is where reassembly bugs live.

## Scaling beyond one node

The current design is a single-node vault. Growing past that means:

1. **Metadata** → PostgreSQL. The JPA layer is portable; the dialect and `schema.sql` are the only
   coupling points.
2. **Chunks** → object storage (S3/R2) behind the `StorageService` interface, which is already the
   only component that touches the filesystem.
3. **Deduplication** → chunk hashes are already content digests; a shared chunk table with
   reference counts would make identical chunks free.
4. **Resumable uploads** → chunk indices already exist; the missing piece is an upload session.

## Project layout

```
├── backend/               Spring Boot service
│   └── src/main/java/com/filevault/
│       ├── config/        typed properties, security configuration
│       ├── controller/    REST endpoints
│       ├── dto/           API representations
│       ├── exception/     domain exceptions, problem-detail handler
│       ├── model/         JPA entities (FileRecord, Chunk)
│       ├── repository/    Spring Data repositories
│       ├── security/      API-key filter
│       ├── service/       compression, chunking, storage, encryption, orchestration
│       ├── util/          SHA-256 helpers
│       └── web/           request-id filter
├── cli/                   shaded command-line client
├── Dockerfile             multi-stage build, non-root runtime
├── docker-compose.yml
└── .github/workflows/ci.yml
```

`FileRecord` is named that, rather than `File`, on purpose: an entity called `File` shadows
`java.io.File` throughout the storage layer, exactly where the confusion would cost most.
