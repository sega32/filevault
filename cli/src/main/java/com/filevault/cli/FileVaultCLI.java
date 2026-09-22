package com.filevault.cli;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Command-line client for FileVault.
 *
 * <pre>
 *   filevault upload &lt;path&gt;
 *   filevault download &lt;file-id&gt; &lt;output-path&gt;
 *   filevault list
 *   filevault info &lt;file-id&gt;
 *   filevault delete &lt;file-id&gt;
 * </pre>
 *
 * <p>Exit codes: {@code 0} success, {@code 1} operation failed, {@code 2} usage error.
 */
public final class FileVaultCLI {

    private static final String DEFAULT_SERVER = "http://localhost:8080/api";
    private static final String RULE = "═".repeat(72);

    private static final int EXIT_OK = 0;
    private static final int EXIT_FAILURE = 1;
    private static final int EXIT_USAGE = 2;

    private final PrintStream out;
    private final PrintStream err;

    FileVaultCLI(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    public static void main(String[] args) {
        System.exit(new FileVaultCLI(System.out, System.err).run(args));
    }

    /** Runs one command. Returns the process exit code rather than calling {@code System.exit}. */
    int run(String[] args) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (CliException e) {
            err.println("Error: " + e.getMessage());
            printUsage(err);
            return EXIT_USAGE;
        }

        if (options.command == null || options.command.equals("help")) {
            printUsage(out);
            return options.command == null ? EXIT_USAGE : EXIT_OK;
        }

        VaultHttpClient client =
                new VaultHttpClient(options.server, options.apiKey, options.apiKeyHeader);

        try {
            return switch (options.command) {
                case "upload" -> upload(client, options.arguments);
                case "download" -> download(client, options.arguments);
                case "list" -> list(client);
                case "info" -> info(client, options.arguments);
                case "delete" -> delete(client, options.arguments);
                case "health" -> health(client);
                default -> {
                    err.println("Error: unknown command '" + options.command + "'");
                    printUsage(err);
                    yield EXIT_USAGE;
                }
            };
        } catch (CliException e) {
            err.println();
            err.println("Error: " + e.getMessage());
            return EXIT_FAILURE;
        }
    }

    // --------------------------------------------------------------- commands

    private int upload(VaultHttpClient client, List<String> arguments) {
        if (arguments.size() != 1) {
            err.println("Usage: filevault upload <path>");
            return EXIT_USAGE;
        }
        Path source = Path.of(arguments.get(0));
        if (!Files.isRegularFile(source)) {
            throw new CliException("Not a readable file: " + source);
        }

        heading("FILE UPLOAD");
        out.println("Server:  " + client.baseUrl());
        out.println("File:    " + source.toAbsolutePath());
        try {
            out.println("Size:    " + humanBytes(Files.size(source)));
        } catch (java.io.IOException e) {
            throw new CliException("Cannot stat " + source + ": " + e.getMessage(), e);
        }
        out.println();
        out.println("Uploading...");

        Instant started = Instant.now();
        FileMetadata metadata = client.upload(source);
        Duration elapsed = Duration.between(started, Instant.now());

        out.println();
        out.println("✓ Upload complete in " + humanDuration(elapsed));
        out.println();
        printMetadata(metadata);
        out.println();
        out.println("Download it later with:");
        out.println("  filevault download " + metadata.fileId() + " " + metadata.filename());
        return EXIT_OK;
    }

    private int download(VaultHttpClient client, List<String> arguments) {
        if (arguments.size() != 2) {
            err.println("Usage: filevault download <file-id> <output-path>");
            return EXIT_USAGE;
        }
        String fileId = arguments.get(0);
        Path destination = Path.of(arguments.get(1));

        heading("FILE DOWNLOAD");
        out.println("Server:   " + client.baseUrl());
        out.println("File ID:  " + fileId);
        out.println("Output:   " + destination.toAbsolutePath());
        out.println();
        out.println("Downloading...");

        Instant started = Instant.now();
        long bytes = client.download(fileId, destination);
        Duration elapsed = Duration.between(started, Instant.now());

        out.println();
        out.println("✓ Downloaded, decompressed and SHA-256 verified");
        out.println("  " + humanBytes(bytes) + " in " + humanDuration(elapsed));
        out.println("  " + destination.toAbsolutePath());
        return EXIT_OK;
    }

    private int list(VaultHttpClient client) {
        heading("STORED FILES");
        List<FileMetadata> files = client.list();
        if (files.isEmpty()) {
            out.println("No files stored yet.");
            return EXIT_OK;
        }

        out.printf(
                Locale.ROOT,
                "%-38s %-28s %12s %12s %9s%n",
                "FILE ID",
                "FILENAME",
                "ORIGINAL",
                "COMPRESSED",
                "SAVED");
        out.println("─".repeat(104));
        for (FileMetadata file : files) {
            out.printf(
                    Locale.ROOT,
                    "%-38s %-28s %12s %12s %8.2f%%%n",
                    file.fileId(),
                    truncate(file.filename(), 28),
                    humanBytes(file.originalSize()),
                    humanBytes(file.compressedSize()),
                    file.compressionRatio());
        }
        out.println();
        out.println("Total files: " + files.size());
        return EXIT_OK;
    }

    private int info(VaultHttpClient client, List<String> arguments) {
        if (arguments.size() != 1) {
            err.println("Usage: filevault info <file-id>");
            return EXIT_USAGE;
        }
        heading("FILE INFO");
        printMetadata(client.info(arguments.get(0)));
        return EXIT_OK;
    }

    private int delete(VaultHttpClient client, List<String> arguments) {
        if (arguments.size() != 1) {
            err.println("Usage: filevault delete <file-id>");
            return EXIT_USAGE;
        }
        String fileId = arguments.get(0);
        client.delete(fileId);
        out.println("✓ Deleted " + fileId);
        return EXIT_OK;
    }

    private int health(VaultHttpClient client) {
        boolean up = client.health();
        out.println(up ? "✓ Server is up: " + client.baseUrl() : "✗ Server is not responding");
        return up ? EXIT_OK : EXIT_FAILURE;
    }

    // ------------------------------------------------------------- formatting

    private void heading(String title) {
        out.println();
        out.println(RULE);
        out.println("  " + title);
        out.println(RULE);
        out.println();
    }

    private void printMetadata(FileMetadata metadata) {
        out.println("File ID:            " + metadata.fileId());
        out.println("Filename:           " + metadata.filename());
        out.println("Content type:       " + orDash(metadata.contentType()));
        out.println("Original size:      " + humanBytes(metadata.originalSize()));
        out.println("Compressed size:    " + humanBytes(metadata.compressedSize()));
        out.printf(Locale.ROOT, "Space saved:        %.2f%%%n", metadata.compressionRatio());
        out.println("Chunks:             " + metadata.chunkCount());
        out.println("SHA-256:            " + orDash(metadata.sha256()));
        out.println("Created:            " + orDash(metadata.createdAt()));
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static String truncate(String value, int width) {
        if (value == null) {
            return "";
        }
        return value.length() <= width ? value : value.substring(0, width - 1) + "…";
    }

    /** Formats a byte count the way a human reads it: 1.5 MB, not 1572864. */
    static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB", "PB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    static String humanDuration(Duration duration) {
        long millis = Math.max(duration.toMillis(), 0);
        if (millis < 1000) {
            return millis + " ms";
        }
        if (millis < 60_000) {
            return String.format(Locale.ROOT, "%.1f s", millis / 1000.0);
        }
        return String.format(Locale.ROOT, "%d m %d s", millis / 60_000, (millis % 60_000) / 1000);
    }

    private void printUsage(PrintStream stream) {
        stream.println(
                """

                FileVault CLI — compressed, chunked, integrity-checked file storage

                USAGE
                  filevault <command> [options]

                COMMANDS
                  upload <path>                  Compress, chunk and store a file
                  download <file-id> <output>    Retrieve, verify and decompress a file
                  list                           List stored files, newest first
                  info <file-id>                 Show metadata for one file
                  delete <file-id>               Delete a file and its chunks
                  health                         Check that the server is reachable
                  help                           Show this message

                OPTIONS
                  --server <url>        API base URL (default: http://localhost:8080/api)
                  --api-key <key>       API key sent with each request
                  --api-key-header <h>  Header carrying the key (default: X-API-Key)

                ENVIRONMENT
                  FILEVAULT_SERVER      Same as --server
                  FILEVAULT_API_KEY     Same as --api-key

                EXAMPLES
                  filevault upload ./report.pdf
                  filevault list
                  filevault download 6f1c... ./report.pdf
                """);
    }

    /** Parsed command line: a command, its positional arguments, and connection options. */
    static final class Options {
        String command;
        List<String> arguments = new ArrayList<>();
        String server = envOrDefault("FILEVAULT_SERVER", DEFAULT_SERVER);
        String apiKey = System.getenv("FILEVAULT_API_KEY");
        String apiKeyHeader = envOrDefault("FILEVAULT_API_KEY_HEADER", "X-API-Key");

        static Options parse(String[] args) {
            Options options = new Options();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--server" -> options.server = requireValue(args, ++i, "--server");
                    case "--api-key" -> options.apiKey = requireValue(args, ++i, "--api-key");
                    case "--api-key-header" ->
                            options.apiKeyHeader = requireValue(args, ++i, "--api-key-header");
                    case "-h", "--help" -> options.command = "help";
                    default -> {
                        if (arg.startsWith("--")) {
                            throw new CliException("unknown option '" + arg + "'");
                        }
                        if (options.command == null) {
                            options.command = arg;
                        } else {
                            options.arguments.add(arg);
                        }
                    }
                }
            }
            return options;
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new CliException(option + " requires a value");
            }
            return args[index];
        }

        private static String envOrDefault(String name, String fallback) {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? fallback : value;
        }
    }
}
