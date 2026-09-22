# FileVault — Complete User Guide

A step-by-step guide from a fresh machine to storing and retrieving files. No prior knowledge of
this project assumed.

If you just want the fastest path, read [Part 1](#part-1--install-java) and
[Part 3](#part-3--your-first-five-minutes) and stop there.

---

## Contents

1. [Install Java](#part-1--install-java)
2. [Build the project](#part-2--build-the-project)
3. [Your first five minutes](#part-3--your-first-five-minutes)
4. [What FileVault actually does](#part-4--what-filevault-actually-does)
5. [Every CLI command](#part-5--every-cli-command)
6. [Using the API directly with curl](#part-6--using-the-api-directly-with-curl)
7. [Configuration reference](#part-7--configuration-reference)
8. [Encryption at rest](#part-8--encryption-at-rest)
9. [Running with Docker](#part-9--running-with-docker)
10. [Where your data lives](#part-10--where-your-data-lives)
11. [Monitoring and health](#part-11--monitoring-and-health)
12. [Running the tests](#part-12--running-the-tests)
13. [Troubleshooting](#part-13--troubleshooting)
14. [Things it deliberately does not do](#part-14--things-it-deliberately-does-not-do)
15. [Reading the code](#part-15--reading-the-code)

---

## Part 1 — Install Java

You need **JDK 21 or newer**. Check what you have:

```bash
java -version
```

If that prints `21.x` or higher, skip ahead. If it says "command not found" or shows an older
version, pick one of these.

### Option A — SDKMAN (macOS/Linux, easiest to manage)

```bash
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install java 21.0.11-tem
```

### Option B — Homebrew (macOS)

```bash
brew install openjdk@21
sudo ln -sfn /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk \
             /Library/Java/JavaVirtualMachines/openjdk-21.jdk
```

### Option C — Direct download, no package manager, no admin rights

This works anywhere and installs nothing system-wide. macOS Apple Silicon shown; change
`mac/aarch64` to `mac/x64`, `linux/x64`, or `windows/x64` as needed.

```bash
mkdir -p ~/java && cd ~/java
curl -fsSL -o jdk.tar.gz \
  "https://api.adoptium.net/v3/binary/latest/21/ga/mac/aarch64/jdk/hotspot/normal/eclipse"
mkdir -p jdk && tar xzf jdk.tar.gz -C jdk --strip-components=1

# macOS path (Linux/Windows: the JDK root is ~/java/jdk itself, with no Contents/Home)
export JAVA_HOME=~/java/jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
java -version
```

Add those two `export` lines to your `~/.zshrc` or `~/.bashrc` to make them permanent.

> **Note:** this is exactly how the toolchain on the machine that built this project was set up —
> it lives at `~/.filevault-toolchain`. If you are on that machine, just run:
> ```bash
> export JAVA_HOME="$HOME/.filevault-toolchain/jdk/Contents/Home"
> export PATH="$JAVA_HOME/bin:$PATH"
> ```

**You do not need to install Maven.** The project ships a wrapper (`./mvnw`) that downloads the
right Maven version by itself.

---

## Part 2 — Build the project

From the project root:

```bash
./mvnw package
```

The first run downloads dependencies and takes a few minutes; later runs take seconds. It compiles
both modules, runs all 70 tests, and produces two files:

| File | What it is |
|---|---|
| `backend/target/filevault-backend-1.0.0.jar` | The server. ~72 MB — it contains Spring Boot and everything else it needs. |
| `cli/target/filevault-cli.jar` | The command-line client. ~327 KB, self-contained. |

You should see `BUILD SUCCESS` at the end. If tests fail, something is genuinely wrong — don't
skip them to get past it.

Useful variations:

```bash
./mvnw clean package          # rebuild from scratch
./mvnw package -DskipTests    # faster, but you're flying blind
./mvnw -pl backend package    # build only the server
./mvnw -pl cli package        # build only the CLI
```

---

## Part 3 — Your first five minutes

### Step 1: create an API key

The server requires authentication and **refuses to start without a key**. That is deliberate — it
means you can never accidentally leave it wide open.

```bash
export FILEVAULT_API_KEY=$(openssl rand -hex 32)
echo $FILEVAULT_API_KEY
```

Copy that value somewhere. You need the same key for the client.

### Step 2: start the server

In terminal 1:

```bash
APP_SECURITY_API_KEYS_0="$FILEVAULT_API_KEY" \
  java -jar backend/target/filevault-backend-1.0.0.jar
```

Wait for a line like:

```
Started FileVaultApplication in 2.34 seconds
```

The server is now at `http://localhost:8080/api`. Leave this terminal running — closing it stops
the server.

### Step 3: talk to it

Open terminal 2. Set the same key here (a new terminal doesn't inherit the variable):

```bash
export FILEVAULT_API_KEY=<paste the key from step 1>
```

Check the server is reachable:

```bash
java -jar cli/target/filevault-cli.jar health
```
```
✓ Server is up: http://localhost:8080/api
```

### Step 4: store a file

```bash
java -jar cli/target/filevault-cli.jar upload ~/Downloads/somefile.pdf
```

Real output from a 7.4 MB test file:

```
════════════════════════════════════════════════════════════════════════
  FILE UPLOAD
════════════════════════════════════════════════════════════════════════

Server:  http://localhost:8080/api
File:    /Users/you/Downloads/somefile.pdf
Size:    7.4 MB

Uploading...

✓ Upload complete in 266 ms

File ID:            ccbc9b5b-84a2-48b1-b763-d458de9f6850
Filename:           somefile.pdf
Content type:       application/pdf
Original size:      7.4 MB
Compressed size:    4.0 MB
Space saved:        45.31%
Chunks:             2
SHA-256:            c3424ac52433a4c92fff8682f0d9cbbed00637150e6bf59735b1026da30403c3
Created:            2026-07-27T15:17:04.029844Z
```

**Save the File ID.** It is how you retrieve the file later. (You can always find it again with
`list`.)

### Step 5: get it back

```bash
java -jar cli/target/filevault-cli.jar download ccbc9b5b-84a2-48b1-b763-d458de9f6850 ./restored.pdf
```

```
✓ Downloaded, decompressed and SHA-256 verified
  7.4 MB in 117 ms
  /Users/you/restored.pdf
```

Prove it is genuinely the same file:

```bash
cmp ~/Downloads/somefile.pdf ./restored.pdf && echo "identical"
```

### Step 6: make life easier

Typing `java -jar ...` every time gets old. Add this to your `~/.zshrc` or `~/.bashrc`:

```bash
export FILEVAULT_API_KEY=<your key>
filevault() { java -jar /full/path/to/cli/target/filevault-cli.jar "$@"; }
```

Then it's just:

```bash
filevault upload report.pdf
filevault list
```

The rest of this guide uses that short form.

---

## Part 4 — What FileVault actually does

Worth understanding, because it explains the output you see.

### When you upload

1. **Digest** — a SHA-256 fingerprint of your original file is computed as it streams past.
2. **Compress** — the bytes are gzipped (level 6). Text shrinks a lot; JPEGs and MP4s barely at
   all, because they are already compressed.
3. **Chunk** — the compressed stream is cut into 4 MB pieces. A 100 MB compressed file becomes 25
   chunks.
4. **Encrypt** (optional, off by default) — each chunk is sealed with AES-256-GCM.
5. **Hash** — each chunk gets its own SHA-256, recorded in the database.
6. **Write** — chunks land in `storage/<file-id>/chunk_00000.bin`, `chunk_00001.bin`, …
7. **Commit** — only once every chunk is safely on disk is the metadata written to the database.
   If anything fails partway, the partial chunks are deleted, so you never get half-stored junk.

### When you download

Everything runs in reverse, with a check at each step:

1. Look up the file's metadata and chunk list.
2. For each chunk in order: read it, confirm its size matches, confirm its SHA-256 matches,
   decrypt it. **This happens before any of its bytes are sent to you** — corrupted data is never
   served as if it were fine.
3. Reassemble, decompress, stream back.
4. The CLI independently re-checks the whole-file SHA-256 and the expected byte count.

### Why chunking matters

Memory use depends on the **chunk size, not the file size**. Both server and client hold about one
chunk at a time. This was tested: an 818 MB file round-tripped byte-for-byte with the server
limited to 192 MB of heap, and 400 MB of incompressible data across 101 chunks did the same. A
naive implementation that loads the whole file would need gigabytes.

### Reading the compression numbers

"Space saved: 45.31%" means the stored copy is about 55% the size of your original.

| Your file | Typical saving |
|---|---|
| Logs, CSV, JSON, source code | 60–99% |
| Word/Excel documents, PDFs | 5–30% |
| JPEG, PNG, MP4, ZIP, already-compressed anything | ~0% |

Seeing 0.00% on a video or a zip is correct behaviour, not a bug. Gzip can even make such files a
fraction of a percent *larger*; FileVault reports that as 0% rather than a confusing negative.

The same applies to very small files, for a different reason: gzip adds a fixed header and
trailer, so a 51-byte text file is stored as 65 bytes. That overhead is irrelevant once files
reach a few kilobytes.

---

## Part 5 — Every CLI command

General form:

```
filevault <command> [arguments] [options]
```

### `upload <path>`

Compresses, chunks and stores a file. Prints the file ID you'll need later.

```bash
filevault upload ./report.pdf
filevault upload /var/log/system.log
```

Only regular files — no directories. To store a folder, tar it first:

```bash
tar czf project.tar.gz ./project && filevault upload project.tar.gz
```

### `download <file-id> <output-path>`

Retrieves, verifies and decompresses. Creates parent directories if needed. **Overwrites the
output path without asking.**

```bash
filevault download ccbc9b5b-84a2-48b1-b763-d458de9f6850 ./restored.pdf
filevault download ccbc9b5b-84a2-48b1-b763-d458de9f6850 ~/backups/restored.pdf
```

### `list`

Every stored file, newest first.

```bash
filevault list
```

```
FILE ID                                FILENAME              ORIGINAL   COMPRESSED     SAVED
────────────────────────────────────────────────────────────────────────────────────────────
ccbc9b5b-84a2-48b1-b763-d458de9f6850   sample.dat              7.4 MB       4.0 MB    45.31%

Total files: 1
```

### `info <file-id>`

Full metadata for one file, including its SHA-256.

```bash
filevault info ccbc9b5b-84a2-48b1-b763-d458de9f6850
```

### `delete <file-id>`

Removes the metadata **and** every chunk from disk. Permanent — there is no trash and no undo.

```bash
filevault delete ccbc9b5b-84a2-48b1-b763-d458de9f6850
```

### `health`

Checks the server is reachable. Works without an API key.

```bash
filevault health
```

### `help`

```bash
filevault help
```

### Options

| Option | Environment variable | Default |
|---|---|---|
| `--server <url>` | `FILEVAULT_SERVER` | `http://localhost:8080/api` |
| `--api-key <key>` | `FILEVAULT_API_KEY` | none |
| `--api-key-header <name>` | `FILEVAULT_API_KEY_HEADER` | `X-API-Key` |

```bash
filevault --server http://192.168.1.50:8080/api --api-key abc123 list
```

### Exit codes

Useful in scripts.

| Code | Meaning |
|---|---|
| `0` | Success |
| `1` | The operation failed (network, auth, integrity, not found) |
| `2` | You typed the command wrong |

```bash
if filevault upload backup.tar.gz; then
    echo "backed up"
else
    echo "backup FAILED" >&2
    exit 1
fi
```

---

## Part 6 — Using the API directly with curl

The CLI is a convenience; the server is a plain REST API you can call from anything. Base URL is
`http://localhost:8080/api`. Every endpoint except `/files/health` needs the `X-API-Key` header.

```bash
KEY=$FILEVAULT_API_KEY
```

**Health** (no key needed):

```bash
curl http://localhost:8080/api/files/health
```
```json
{"message":"FileVault server is running","status":"UP"}
```

**Upload** — note `file=@`, and that the form field must be named `file`:

```bash
curl -X POST -H "X-API-Key: $KEY" \
     -F "file=@./report.pdf" \
     http://localhost:8080/api/files/upload
```

**List:**

```bash
curl -H "X-API-Key: $KEY" http://localhost:8080/api/files
```

**Metadata for one file:**

```bash
curl -H "X-API-Key: $KEY" http://localhost:8080/api/files/<file-id>
```

**Download** (`-o` writes to a file, `-J` is not needed since we name it ourselves):

```bash
curl -H "X-API-Key: $KEY" -o restored.pdf \
     http://localhost:8080/api/files/<file-id>/download
```

**Delete:**

```bash
curl -X DELETE -H "X-API-Key: $KEY" http://localhost:8080/api/files/<file-id>
```

**Vault statistics:**

```bash
curl -H "X-API-Key: $KEY" http://localhost:8080/api/files/stats
```
```json
{"files":1,"originalBytes":7712104,"storedBytes":4218043,
 "spaceSavedPercent":45.3,"usableSpaceBytes":206079819776,"chunkSizeBytes":4194304}
```

### Errors

Failures come back as standard problem documents, not HTML error pages:

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

| Status | Meaning |
|---|---|
| `400` | Bad request — malformed file id, empty upload |
| `401` | Missing or wrong API key |
| `404` | No such file |
| `413` | Upload exceeds the size limit |
| `500` | Server-side failure, including integrity verification failures |

### Two response headers worth knowing

Downloads carry `X-Content-SHA256` (the digest of your original file) and an accurate
`Content-Length`. If you write your own client, check both — that's how you detect a transfer that
was cut short.

---

## Part 7 — Configuration reference

Every setting can be given three ways. Environment variables are usually easiest:

```bash
APP_CHUNK_SIZE=8388608 java -jar backend/target/filevault-backend-1.0.0.jar
```

Or as flags:

```bash
java -jar backend/target/filevault-backend-1.0.0.jar --app.chunk.size=8388608
```

> **zsh users:** the shell tries to expand `[` and `]`. Quote any flag with an index:
> ```bash
> java -jar ... '--app.security.api-keys[0]=your-key'
> ```
> Using the `APP_SECURITY_API_KEYS_0` environment variable avoids the problem entirely.

| Setting | Environment variable | Default | What it does |
|---|---|---|---|
| `server.port` | `SERVER_PORT` | `8080` | Port to listen on |
| `app.storage.path` | `APP_STORAGE_PATH` | `./storage` | Where chunk files go |
| `app.chunk.size` | `APP_CHUNK_SIZE` | `4194304` | Bytes per chunk (4 MB) |
| `app.security.enabled` | `APP_SECURITY_ENABLED` | `true` | API-key authentication |
| `app.security.api-keys[0]` | `APP_SECURITY_API_KEYS_0` | — | An accepted key (required) |
| `app.security.header` | `APP_SECURITY_HEADER` | `X-API-Key` | Header carrying the key |
| `app.security.cors-origins[0]` | `APP_SECURITY_CORS_ORIGINS_0` | empty | Browser origins allowed to call the API |
| `app.encryption.enabled` | `APP_ENCRYPTION_ENABLED` | `false` | Encrypt chunks on disk |
| `app.encryption.key` | `APP_ENCRYPTION_KEY` | — | base64 32-byte key (required if enabled) |
| `spring.datasource.url` | `SPRING_DATASOURCE_URL` | `jdbc:sqlite:./filevault.db?...` | Database location |
| `spring.servlet.multipart.max-file-size` | `SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE` | `5GB` | Largest accepted upload |

### Multiple API keys

Useful when rotating keys or giving different people their own:

```bash
APP_SECURITY_API_KEYS_0="$KEY_ALICE" \
APP_SECURITY_API_KEYS_1="$KEY_BOB" \
java -jar backend/target/filevault-backend-1.0.0.jar
```

Both work. Note there is no per-user separation — see [Part 14](#part-14--things-it-deliberately-does-not-do).

### Turning authentication off

Only for local experimentation, on a machine nobody else can reach:

```bash
APP_SECURITY_ENABLED=false java -jar backend/target/filevault-backend-1.0.0.jar
```

It logs a deliberately loud warning when you do this.

### Changing the chunk size

Bigger chunks mean fewer files and slightly less overhead, but more memory per request. Smaller
chunks mean the opposite. 4 MB is a sensible default; there is rarely a reason to change it.
**Changing it does not affect already-stored files** — each file remembers its own chunk layout,
so old files keep working.

---

## Part 8 — Encryption at rest

By default chunks sit on disk as plain (compressed) data. Anyone with access to the `storage/`
folder can read them. Encryption fixes that.

### Turning it on

```bash
export FILEVAULT_ENCRYPTION_KEY=$(openssl rand -base64 32)
echo "$FILEVAULT_ENCRYPTION_KEY"      # SAVE THIS SOMEWHERE SAFE

APP_SECURITY_API_KEYS_0="$FILEVAULT_API_KEY" \
APP_ENCRYPTION_ENABLED=true \
APP_ENCRYPTION_KEY="$FILEVAULT_ENCRYPTION_KEY" \
java -jar backend/target/filevault-backend-1.0.0.jar
```

The startup log confirms it:

```
Storage root /path/to/storage ready (at-rest encryption: enabled)
```

Each chunk is sealed with AES-256-GCM using a fresh random IV, and GCM authenticates as well as
encrypts — a tampered chunk fails to decrypt rather than producing garbage.

You can see the difference. An unencrypted chunk starts with the gzip magic bytes `1f 8b`; an
encrypted one is indistinguishable from noise:

```bash
xxd -l 16 storage/<file-id>/chunk_00000.bin
```
```
00000000: 8a7c f706 f71d db89 dbb5 edb8 e7e7 4f10  .|............O.
```

### Four things to know before you enable it

1. **Lose the key, lose the data.** There is no recovery. Store it in a password manager or a
   secret manager, never next to the storage folder.
2. **It only applies going forward.** Chunks written before you enabled it stay plaintext. There
   is no re-encryption pass.
3. **There is no key rotation.** Changing the key makes existing chunks unreadable.
4. **Enabling it without a valid key is a startup failure**, on purpose — it will never silently
   fall back to storing plaintext.

---

## Part 9 — Running with Docker

If you'd rather not install Java at all, and you have Docker:

```bash
export FILEVAULT_API_KEY=$(openssl rand -hex 32)
docker compose up --build
```

That builds the image and starts the server on port 8080, with data on a named volume called
`filevault-data`. The container runs as a non-root user with a read-only root filesystem.

With encryption:

```bash
export FILEVAULT_API_KEY=$(openssl rand -hex 32)
export FILEVAULT_ENCRYPTION_ENABLED=true
export FILEVAULT_ENCRYPTION_KEY=$(openssl rand -base64 32)
docker compose up --build
```

Useful commands:

```bash
docker compose logs -f          # follow the logs
docker compose down             # stop (data survives on the volume)
docker compose down -v          # stop AND DELETE ALL STORED FILES
docker volume inspect filevault-data
```

The CLI still runs on your host and talks to the container normally — nothing changes.

---

## Part 10 — Where your data lives

Running the jar directly, from the directory you started it in:

```
./storage/                                  chunk files
  └── ccbc9b5b-84a2-48b1-b763-d458de9f6850/
        ├── chunk_00000.bin
        └── chunk_00001.bin
./filevault.db                              SQLite metadata
./filevault.db-wal, ./filevault.db-shm      SQLite working files
```

Under Docker, all of that lives at `/data` inside the container, on the `filevault-data` volume.

### The important backup rule

**Back up `storage/` and `filevault.db` together.** Either one alone is worthless: the database
without the chunks lists files whose data is gone, and the chunks without the database are
anonymous blobs with no filenames, order, or hashes.

Simplest safe backup — stop the server first:

```bash
tar czf filevault-backup-$(date +%F).tar.gz storage filevault.db
```

To back up while running, copy the database consistently rather than with `cp`:

```bash
sqlite3 filevault.db ".backup 'backup.db'"
tar czf filevault-backup-$(date +%F).tar.gz storage backup.db
```

### Don't hand-edit any of it

Renaming, moving or editing chunk files will fail the SHA-256 check on the next download — which
is exactly what it is designed to do. Use `delete` to remove files.

### Running two servers on the same data

Don't. SQLite allows a single writer; point one server at one database.

---

## Part 11 — Monitoring and health

| Endpoint | Auth needed | Purpose |
|---|---|---|
| `/api/files/health` | no | Simple up/down check, for load balancers |
| `/api/actuator/health` | yes | Detailed health, including disk space |
| `/api/files/stats` | yes | File count, bytes stored, space saved, free disk |
| `/api/actuator/metrics` | yes | List of available metrics |
| `/api/actuator/prometheus` | yes | Prometheus scrape format |

Custom metrics, if you're wiring up monitoring:

| Metric | Meaning |
|---|---|
| `filevault.uploads` | Files stored |
| `filevault.downloads` | Successful retrievals |
| `filevault.integrity.failures` | **Corrupted data detected — alert on any non-zero value** |
| `filevault.upload.duration` | Upload timing |

```bash
curl -H "X-API-Key: $KEY" \
     http://localhost:8080/api/actuator/metrics/filevault.integrity.failures
```

### Logs

Every log line carries a request id, and every response includes it as `X-Request-Id`. If
something goes wrong, grab that id from the response and grep the server log for it — you'll get
exactly the lines for that one request. You can also send your own `X-Request-Id` to trace a
request from your side.

The one log line to care about:

```
ERROR ... Integrity failure while streaming <file-id>: Chunk 3 ... failed SHA-256 verification
```

That means a stored chunk is damaged — bad disk, or something modified the files. Restore that
file from backup.

---

## Part 12 — Running the tests

```bash
./mvnw verify
```

70 tests: compression round trips, chunk-boundary handling, corruption and tamper detection,
encryption, filename sanitisation, path-traversal defence, and full end-to-end HTTP tests against
a real database and real filesystem.

```bash
./mvnw -pl backend test                                   # backend only
./mvnw -pl backend test -Dtest=FileVaultIntegrationTest   # one class
```

Reports land in `backend/target/surefire-reports/`.

You will see two `ERROR` lines about integrity failures scroll past during the run. Those are
expected — they come from the tests that deliberately corrupt a chunk to prove detection works. As
long as the final line says `BUILD SUCCESS`, everything passed.

---

## Part 13 — Troubleshooting

### `No API keys configured` and the server won't start

Working as designed. Provide a key:

```bash
export FILEVAULT_API_KEY=$(openssl rand -hex 32)
APP_SECURITY_API_KEYS_0="$FILEVAULT_API_KEY" java -jar backend/target/filevault-backend-1.0.0.jar
```

### `HTTP 401` from the CLI

The key the client sends doesn't match the server's. Most often the client terminal never got the
variable:

```bash
echo $FILEVAULT_API_KEY     # empty? that's your problem
```

Remember each new terminal needs it set, unless it's in your shell profile.

### `Cannot connect to the FileVault server`

The backend isn't running, or you're pointing at the wrong address. Check terminal 1 is still
alive, then:

```bash
curl http://localhost:8080/api/files/health
```

### `Address already in use`

Something already holds port 8080.

```bash
lsof -i :8080                  # see what it is
SERVER_PORT=9090 java -jar backend/target/filevault-backend-1.0.0.jar
```

Then point the client at it: `filevault --server http://localhost:9090/api list`

### `Download ... ended early` / `Download ... is incomplete`

A stored chunk failed verification partway through. The file on disk is damaged. Check the server
log for `Integrity failure` to see which chunk, and restore from backup. The partial download is
left in place so you can inspect it — don't trust it.

### `File id must be a UUID`

You passed something that isn't a file ID. Run `filevault list` to get the real one — it looks
like `ccbc9b5b-84a2-48b1-b763-d458de9f6850`.

### `Uploaded file is empty`

Zero-byte files are rejected. There is nothing to store.

### `command not found: java` after opening a new terminal

Your `JAVA_HOME`/`PATH` exports were only set for that session. Put them in `~/.zshrc` or
`~/.bashrc`.

### `zsh: no matches found: --app.security.api-keys[0]=...`

Zsh is expanding the brackets. Quote the whole argument, or use the environment variable form
(`APP_SECURITY_API_KEYS_0=...`).

### `database is locked`

Two servers are using the same database file. Run only one.

### The build fails on a fresh machine

Confirm the JDK version — this needs 21+, and an older JDK gives confusing compilation errors:

```bash
java -version
./mvnw -v        # shows which JDK Maven is actually using
```

---

## Part 14 — Things it deliberately does not do

Worth knowing before you rely on it for anything that matters.

- **No HTTPS.** Traffic, including your API key, is plain HTTP. Fine on `localhost`; if you expose
  it on a network, put nginx or Caddy in front to terminate TLS.
- **No user accounts.** Any valid API key can read, download and delete *every* file. There is no
  concept of "my files" versus "yours".
- **No rate limits or quotas.** A client can upload until the disk fills.
- **No audit log** of who did what.
- **No key rotation**, for API keys or the encryption key.
- **No deduplication.** Uploading the same file twice stores it twice.
- **No versioning.** Uploading a file with an existing name creates a separate, independent entry.
- **Single node.** One server, one disk, one database. No replication or failover.
- **One honest caveat about integrity:** corruption is *always* detected and never served as valid
  data. But if the damage is in a chunk after the first, it's caught partway through the download —
  after HTTP already committed a `200 OK`. The server aborts the transfer, logs it at ERROR and
  counts it; the client notices the short read and fails loudly. Catching it earlier would mean
  reading every file twice, which was judged not worth the cost.

---

## Part 15 — Reading the code

If your friend wants to poke around, the useful entry points, in reading order:

| File | What to look at |
|---|---|
| `backend/.../service/FileService.java` | The heart of it — upload and download orchestration |
| `backend/.../service/ChunkingService.java` | How the stream is split without buffering the file |
| `backend/.../service/StorageService.java` | Disk I/O, hash verification, path-traversal defence |
| `backend/.../controller/FileController.java` | The REST endpoints |
| `backend/.../security/ApiKeyAuthFilter.java` | Authentication |
| `backend/src/main/resources/db/schema.sql` | The database, in 20 readable lines |
| `cli/.../FileVaultCLI.java` | The command-line client |

Layout:

```
backend/src/main/java/com/filevault/
  config/      settings and security setup
  controller/  REST endpoints
  dto/         what the API returns
  exception/   error types and the handler that formats them
  model/       database entities
  repository/  database queries
  security/    API-key filter
  service/     the actual logic
  util/        SHA-256 helpers
  web/         request-id tracking
```

One naming note that trips people up: the entity is called `FileRecord`, not `File`. That's
deliberate — a class named `File` would shadow Java's built-in `java.io.File` throughout the
storage layer, which is precisely where that confusion would be most expensive.

### Other documents

| File | Contents |
|---|---|
| `README.md` | Architecture, API reference, design decisions and trade-offs |
| `QUICK_START.md` | The five-minute version |
| `BUILD_AND_RUN.md` | Build options, deployment, JVM tuning |
| `PROJECT_SUMMARY.txt` | One-page overview of the whole system |
