package com.filevault.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.filevault.cli.FileVaultCLI.Options;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FileVaultCLITest {

    @ParameterizedTest
    @CsvSource({
        "0, 0 B",
        "512, 512 B",
        "1024, 1.0 KB",
        "1536, 1.5 KB",
        "1048576, 1.0 MB",
        "1073741824, 1.0 GB"
    })
    void formatsByteCountsForHumans(long bytes, String expected) {
        assertThat(FileVaultCLI.humanBytes(bytes)).isEqualTo(expected);
    }

    @Test
    void formatsDurations() {
        assertThat(FileVaultCLI.humanDuration(Duration.ofMillis(250))).isEqualTo("250 ms");
        assertThat(FileVaultCLI.humanDuration(Duration.ofMillis(1500))).isEqualTo("1.5 s");
        assertThat(FileVaultCLI.humanDuration(Duration.ofSeconds(125))).isEqualTo("2 m 5 s");
    }

    @Test
    void parsesACommandWithArguments() {
        Options options = Options.parse(new String[] {"download", "abc", "out.bin"});

        assertThat(options.command).isEqualTo("download");
        assertThat(options.arguments).containsExactly("abc", "out.bin");
    }

    @Test
    void parsesConnectionOptions() {
        Options options =
                Options.parse(
                        new String[] {"--server", "http://vault:9000/api", "--api-key", "k", "list"});

        assertThat(options.server).isEqualTo("http://vault:9000/api");
        assertThat(options.apiKey).isEqualTo("k");
        assertThat(options.command).isEqualTo("list");
    }

    @Test
    void rejectsAnOptionWithoutAValue() {
        assertThatThrownBy(() -> Options.parse(new String[] {"--server"}))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("--server");
    }

    @Test
    void rejectsUnknownOptions() {
        assertThatThrownBy(() -> Options.parse(new String[] {"--nope", "list"}))
                .isInstanceOf(CliException.class);
    }

    @Test
    void helpExitsSuccessfully() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode =
                new FileVaultCLI(new PrintStream(out), new PrintStream(err)).run(new String[] {"help"});

        assertThat(exitCode).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("USAGE", "upload", "download");
    }

    @Test
    void noArgumentsIsAUsageError() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int exitCode =
                new FileVaultCLI(new PrintStream(out), new PrintStream(out)).run(new String[] {});

        assertThat(exitCode).isEqualTo(2);
    }

    @Test
    void unknownCommandIsAUsageError() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int exitCode =
                new FileVaultCLI(new PrintStream(out), new PrintStream(out))
                        .run(new String[] {"frobnicate"});

        assertThat(exitCode).isEqualTo(2);
    }
}
