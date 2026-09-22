package com.filevault.exception;

/** Domain exceptions, grouped so the failure vocabulary of the system is readable in one place. */
public final class FileVaultExceptions {

    private FileVaultExceptions() {}

    /** No file with the requested id exists. Maps to HTTP 404. */
    public static class FileNotFoundException extends RuntimeException {
        private final String fileId;

        public FileNotFoundException(String fileId) {
            super("No file with id " + fileId);
            this.fileId = fileId;
        }

        public String getFileId() {
            return fileId;
        }
    }

    /**
     * Stored bytes did not match their recorded SHA-256, or a chunk is missing. Maps to HTTP 500 —
     * the client did nothing wrong; the vault did.
     */
    public static class IntegrityException extends RuntimeException {
        public IntegrityException(String message) {
            super(message);
        }
    }

    /** The request itself was unusable (empty upload, malformed id). Maps to HTTP 400. */
    public static class InvalidRequestException extends RuntimeException {
        public InvalidRequestException(String message) {
            super(message);
        }
    }

    /** Disk or filesystem failure while reading or writing chunks. Maps to HTTP 500. */
    public static class StorageException extends RuntimeException {
        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }

        public StorageException(String message) {
            super(message);
        }
    }
}
