package com.eunsearch.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ConfigRecoveryTest {
    @TempDir Path directory;

    @Test void malformedConfigIsPreserved() throws Exception {
        Path file = directory.resolve("eun_search.json");
        String broken = "{\"scans\":[{\"tag\":\"warehouse\"}";
        Files.writeString(file, broken);
        assertTrue(ModConfig.load(file).getScans().isEmpty());
        assertEquals(broken, Files.readString(file));
    }

    @Test void nullConfigIsPreserved() throws Exception {
        Path file = directory.resolve("eun_search.json");
        Files.writeString(file, "null");
        assertTrue(ModConfig.load(file).getScans().isEmpty());
        assertEquals("null", Files.readString(file));
    }

    @Test void missingConfigIsCreated() {
        Path file = directory.resolve("eun_search.json");
        assertTrue(ModConfig.load(file).getScans().isEmpty());
        assertTrue(Files.exists(file));
    }
}
