package com.genomics.dedup.cache;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class HashCacheTest {

    @Test
    void putAndGet_sameTimestamp_returnsHash(@TempDir Path tmp) {
        Path dbPath = tmp.resolve("cache.db");
        Path filePath = tmp.resolve("sample.vcf");
        try (HashCache cache = new HashCache(dbPath)) {
            cache.put(filePath, 1000L, "abc123hash");
            assertThat(cache.get(filePath, 1000L)).isEqualTo("abc123hash");
        }
    }

    @Test
    void get_differentTimestamp_returnsNull(@TempDir Path tmp) {
        Path dbPath = tmp.resolve("cache.db");
        Path filePath = tmp.resolve("sample.vcf");
        try (HashCache cache = new HashCache(dbPath)) {
            cache.put(filePath, 1000L, "abc123hash");
            assertThat(cache.get(filePath, 9999L)).isNull();
        }
    }

    @Test
    void get_unknownPath_returnsNull(@TempDir Path tmp) {
        Path dbPath = tmp.resolve("cache.db");
        try (HashCache cache = new HashCache(dbPath)) {
            assertThat(cache.get(tmp.resolve("unknown.vcf"), 1000L)).isNull();
        }
    }

    @Test
    void put_overwritesExistingEntry(@TempDir Path tmp) {
        Path dbPath = tmp.resolve("cache.db");
        Path filePath = tmp.resolve("sample.vcf");
        try (HashCache cache = new HashCache(dbPath)) {
            cache.put(filePath, 1000L, "oldhash");
            cache.put(filePath, 2000L, "newhash");
            assertThat(cache.get(filePath, 2000L)).isEqualTo("newhash");
            assertThat(cache.get(filePath, 1000L)).isNull();
        }
    }

    @Test
    void persistsAcrossReopens(@TempDir Path tmp) {
        Path dbPath   = tmp.resolve("cache.db");
        Path filePath = tmp.resolve("sample.vcf");

        try (HashCache cache = new HashCache(dbPath)) {
            cache.put(filePath, 5000L, "persistedhash");
            cache.commit();
        }

        try (HashCache cache = new HashCache(dbPath)) {
            assertThat(cache.get(filePath, 5000L)).isEqualTo("persistedhash");
        }
    }

    @Test
    void size_returnsCorrectCount(@TempDir Path tmp) {
        Path dbPath = tmp.resolve("cache.db");
        try (HashCache cache = new HashCache(dbPath)) {
            assertThat(cache.size()).isEqualTo(0);
            cache.put(tmp.resolve("a.vcf"), 100L, "hash1");
            cache.put(tmp.resolve("b.vcf"), 200L, "hash2");
            assertThat(cache.size()).isEqualTo(2);
        }
    }

    @Test
    void commit_doesNotThrow(@TempDir Path tmp) {
        Path dbPath = tmp.resolve("cache.db");
        try (HashCache cache = new HashCache(dbPath)) {
            cache.put(tmp.resolve("x.vcf"), 1L, "hash");
            assertThatCode(cache::commit).doesNotThrowAnyException();
        }
    }
}
