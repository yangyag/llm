package com.llm.app.board.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.llm.app.board.api.upload.GeneratedAttachmentPolicy;
import com.llm.app.board.exception.AttachmentTooLargeException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

class GeneratedAttachmentPolicyTest {

    @Test
    void policyShouldExposeConfiguredGeneratedAttachmentBytes() {
        AttachmentStorageService storage = new AttachmentStorageService(
            "build-local/generated-attachment-policy-test",
            DataSize.ofMegabytes(2),
            DataSize.ofBytes(12345)
        );

        GeneratedAttachmentPolicy policy = storage;

        assertThat(policy.getMaxGeneratedFileSizeBytes()).isEqualTo(12345L);
    }

    @Test
    void generatedAttachmentAtConfiguredLimitShouldBeStored(@TempDir Path tempDir) throws IOException {
        Path root = tempDir.resolve("attachments");
        Path source = tempDir.resolve("source.bin");
        byte[] bytes = "12345".getBytes();
        Files.write(source, bytes);
        AttachmentStorageService storage = new AttachmentStorageService(
            root.toString(),
            DataSize.ofMegabytes(2),
            DataSize.ofBytes(bytes.length)
        );

        AttachmentStorageService.StoredAttachment stored = storage.store(source, "exact.bin", "application/octet-stream");

        assertThat(stored.size()).isEqualTo(bytes.length);
        assertThat(Files.readAllBytes(root.resolve(stored.storagePath()))).isEqualTo(bytes);
    }

    @Test
    void generatedAttachmentOverConfiguredLimitShouldBeRejectedBeforeStorage(@TempDir Path tempDir) throws IOException {
        Path root = tempDir.resolve("attachments");
        Path source = tempDir.resolve("source.bin");
        Files.write(source, "123456".getBytes());
        AttachmentStorageService storage = new AttachmentStorageService(
            root.toString(),
            DataSize.ofMegabytes(2),
            DataSize.ofBytes(5)
        );

        assertThatThrownBy(() -> storage.store(source, "over.bin", "application/octet-stream"))
            .isInstanceOf(AttachmentTooLargeException.class);
        assertThat(Files.exists(root)).isFalse();
    }
}
