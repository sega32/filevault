-- FileVault schema. Owned by this file, not by Hibernate: ddl-auto is none, so the
-- shape of the database is reviewable in version control.

CREATE TABLE IF NOT EXISTS files (
    file_id         TEXT PRIMARY KEY,
    filename        TEXT    NOT NULL,
    content_type    TEXT,
    original_size   BIGINT  NOT NULL,
    compressed_size BIGINT  NOT NULL,
    original_sha256 TEXT    NOT NULL,
    chunk_count     INTEGER NOT NULL,
    created_at      TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS chunks (
    file_id     TEXT    NOT NULL,
    chunk_index INTEGER NOT NULL,
    chunk_hash  TEXT    NOT NULL,
    chunk_size  BIGINT  NOT NULL,
    PRIMARY KEY (file_id, chunk_index),
    FOREIGN KEY (file_id) REFERENCES files (file_id) ON DELETE CASCADE
);

-- Listing is always "newest first"; this keeps it off a full scan as the vault grows.
CREATE INDEX IF NOT EXISTS idx_files_created_at ON files (created_at DESC);
