package com.genomics.dedup.hash;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Arrays;
import static org.assertj.core.api.Assertions.*;

class FileHasherTest {

    private FileHasher hasher;

    @BeforeEach
    void setUp() { hasher = new FileHasher(256 * 1024); }

    @Test
    void sha256_knownContent_returnsCorrectHash(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("test.vcf");
        byte[] content = "hello world".getBytes();
        Files.write(file, content);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        String expected = FileHasher.toHex(md.digest(content));
        assertThat(hasher.sha256(file)).isEqualTo(expected);
    }

    @Test
    void sha256_emptyFile_returnsKnownHash(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("empty.vcf");
        Files.writeString(file, "");
        assertThat(hasher.sha256(file))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void sha256_identicalContent_sameHash(@TempDir Path tmp) throws Exception {
        String content = "##fileformat=VCFv4.2\n#CHROM\tPOS\tID\tREF\tALT\n";
        Path f1 = tmp.resolve("sample1.vcf");
        Path f2 = tmp.resolve("copy.vcf");
        Files.writeString(f1, content);
        Files.writeString(f2, content);
        assertThat(hasher.sha256(f1)).isEqualTo(hasher.sha256(f2));
    }

    @Test
    void sha256_differentContent_differentHash(@TempDir Path tmp) throws Exception {
        Path f1 = tmp.resolve("a.vcf");
        Path f2 = tmp.resolve("b.vcf");
        Files.writeString(f1, "content A");
        Files.writeString(f2, "content B");
        assertThat(hasher.sha256(f1)).isNotEqualTo(hasher.sha256(f2));
    }

    @Test
    void sha256_largeFile_chunkedReadSucceeds(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("large.vcf");
        byte[] oneMB = new byte[1024 * 1024];
        Arrays.fill(oneMB, (byte) 65);
        try (var os = Files.newOutputStream(file)) {
            for (int i = 0; i < 5; i++) os.write(oneMB); // 5 MB
        }
        String hash = hasher.sha256(file);
        assertThat(hash).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void sha256_returnsLowercase64CharHex(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("x.vcf");
        Files.writeString(file, "test data");
        String hash = hasher.sha256(file);
        assertThat(hash).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void sha256_missingFile_throwsIOException(@TempDir Path tmp) {
        Path missing = tmp.resolve("nonexistent.vcf");
        assertThatThrownBy(() -> hasher.sha256(missing))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void constructor_bufferTooSmall_throws() {
        assertThatThrownBy(() -> new FileHasher(100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toHex_allZeros_returnsZeroString() {
        assertThat(FileHasher.toHex(new byte[4])).isEqualTo("00000000");
    }

    @Test
    void toHex_allFF_returnsFFString() {
        byte[] input = new byte[4];
        Arrays.fill(input, (byte) 0xFF);
        assertThat(FileHasher.toHex(input)).isEqualTo("ffffffff");
    }
}
