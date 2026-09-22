package com.filevault;

import com.filevault.config.FileVaultProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Entry point for the FileVault backend.
 *
 * <p>FileVault stores files gzip-compressed, split into fixed-size chunks on disk, with
 * metadata (and a SHA-256 digest per chunk) tracked in SQLite via Spring Data JPA.
 */
@SpringBootApplication
@EnableConfigurationProperties(FileVaultProperties.class)
public class FileVaultApplication {

    public static void main(String[] args) {
        SpringApplication.run(FileVaultApplication.class, args);
    }
}
