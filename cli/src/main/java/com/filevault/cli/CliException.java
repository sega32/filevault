package com.filevault.cli;

/** An error worth showing the user as a message rather than as a stack trace. */
public class CliException extends RuntimeException {

    public CliException(String message) {
        super(message);
    }

    public CliException(String message, Throwable cause) {
        super(message, cause);
    }
}
