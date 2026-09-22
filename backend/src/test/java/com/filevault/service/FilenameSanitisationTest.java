package com.filevault.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.filevault.exception.FileVaultExceptions.InvalidRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class FilenameSanitisationTest {

    @ParameterizedTest
    @CsvSource({
        "report.pdf, report.pdf",
        "'  spaced.txt  ', spaced.txt",
        "/etc/passwd, passwd",
        "../../secret.key, secret.key",
        "C:\\Windows\\System32\\config, config",
        "folder/sub/file.tar.gz, file.tar.gz"
    })
    void reducesToASafeBasename(String input, String expected) {
        assertThat(FileService.sanitiseFilename(input)).isEqualTo(expected);
    }

    @Test
    void stripsControlCharacters() {
        assertThat(FileService.sanitiseFilename("re\u0000port\u0007.txt")).isEqualTo("report.txt");
    }

    @Test
    void truncatesOverlongNames() {
        String long_ = "x".repeat(400) + ".txt";

        assertThat(FileService.sanitiseFilename(long_)).hasSize(255);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "/", "..", "."})
    void rejectsUnusableNames(String input) {
        assertThatThrownBy(() -> FileService.sanitiseFilename(input))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> FileService.sanitiseFilename(null))
                .isInstanceOf(InvalidRequestException.class);
    }
}
