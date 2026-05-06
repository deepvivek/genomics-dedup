package com.genomics.dedup.report;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DuplicateGroupTest {

    @Test
    void getWastedBytes_twoFiles_returnsOneCopyWorth() {
        List<Path> paths = List.of(Path.of("/a.vcf"), Path.of("/b.vcf"));
        DuplicateGroup g = new DuplicateGroup(1, "hash64chars........................................................", paths, 1_073_741_824L);
        assertThat(g.getWastedBytes()).isEqualTo(1_073_741_824L);
    }

    @Test
    void getWastedBytes_threeFiles_returnsTwoCopiesWorth() {
        List<Path> paths = List.of(Path.of("/a.vcf"), Path.of("/b.vcf"), Path.of("/c.vcf"));
        DuplicateGroup g = new DuplicateGroup(1, "x".repeat(64), paths, 1000);
        assertThat(g.getWastedBytes()).isEqualTo(2000);
    }

    @Test
    void getOriginalPath_firstPathIsOriginal() {
        Path first = Path.of("/alpha/a.vcf");
        Path second = Path.of("/beta/b.vcf");
        DuplicateGroup g = new DuplicateGroup(1, "x".repeat(64),
                List.of(first, second), 100);
        assertThat(g.getOriginalPath()).isEqualTo(first);
    }

    @Test
    void getDuplicates_excludesFirstPath() {
        Path first  = Path.of("/a.vcf");
        Path second = Path.of("/b.vcf");
        Path third  = Path.of("/c.vcf");
        DuplicateGroup g = new DuplicateGroup(1, "x".repeat(64),
                List.of(first, second, third), 100);
        assertThat(g.getDuplicates()).containsExactly(second, third);
    }

    @Test
    void getSha256Short_returns12CharsPlusDots() {
        String fullHash = "abcdef123456" + "x".repeat(52);
        DuplicateGroup g = new DuplicateGroup(1, fullHash, List.of(Path.of("/a.vcf"), Path.of("/b.vcf")), 10);
        assertThat(g.getSha256Short()).startsWith("abcdef123456").endsWith("...");
        assertThat(g.getSha256Short()).hasSize(15);
    }

    @Test
    void isSpanningDirectories_sameDir_returnsFalse(@TempDir Path tmp) {
        Path root = tmp;
        List<Path> paths = List.of(
                root.resolve("batch1/a.vcf"),
                root.resolve("batch1/b.vcf"));
        DuplicateGroup g = new DuplicateGroup(1, "x".repeat(64), paths, 100);
        assertThat(g.isSpanningDirectories(root)).isFalse();
    }

    @Test
    void isSpanningDirectories_differentTopDirs_returnsTrue(@TempDir Path tmp) {
        Path root = tmp;
        List<Path> paths = List.of(
                root.resolve("patients/a.vcf"),
                root.resolve("backup/a.vcf"));
        DuplicateGroup g = new DuplicateGroup(1, "x".repeat(64), paths, 100);
        assertThat(g.isSpanningDirectories(root)).isTrue();
    }

    @Test
    void humanSize_bytes() {
        assertThat(DuplicateGroup.humanSize(500)).isEqualTo("500 B");
    }

    @Test
    void humanSize_kilobytes() {
        assertThat(DuplicateGroup.humanSize(2048)).isEqualTo("2.0 KB");
    }

    @Test
    void humanSize_megabytes() {
        assertThat(DuplicateGroup.humanSize(5 * 1024 * 1024)).isEqualTo("5.0 MB");
    }

    @Test
    void humanSize_gigabytes() {
        assertThat(DuplicateGroup.humanSize(2_147_483_648L)).isEqualTo("2.0 GB");
    }

    @Test
    void humanSize_terabytes() {
        assertThat(DuplicateGroup.humanSize(1_099_511_627_776L)).isEqualTo("1.0 TB");
    }

    @Test
    void getFileCount_correctCount() {
        List<Path> paths = List.of(Path.of("/a.vcf"), Path.of("/b.vcf"), Path.of("/c.vcf"));
        DuplicateGroup g = new DuplicateGroup(1, "x".repeat(64), paths, 100);
        assertThat(g.getFileCount()).isEqualTo(3);
    }
}
