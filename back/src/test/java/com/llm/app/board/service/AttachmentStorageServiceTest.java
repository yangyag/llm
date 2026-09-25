package com.llm.app.board.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AttachmentStorageServiceTest {

	@Test
	void shouldSanitizeClientFilenames() {
		assertThat(AttachmentStorageService.sanitizeOriginalFilename(null)).isEqualTo("attachment");
		assertThat(AttachmentStorageService.sanitizeOriginalFilename("")).isEqualTo("attachment");
		assertThat(AttachmentStorageService.sanitizeOriginalFilename("/")).isEqualTo("attachment");
		assertThat(AttachmentStorageService.sanitizeOriginalFilename("dir/..")).isEqualTo("attachment");
		assertThat(AttachmentStorageService.sanitizeOriginalFilename("\u0000\r\n")).isEqualTo("attachment");
		assertThat(AttachmentStorageService.sanitizeOriginalFilename("../../etc/passwd")).isEqualTo("passwd");
		assertThat(AttachmentStorageService.sanitizeOriginalFilename("C:\\Users\\me\\보고서.pdf")).isEqualTo("보고서.pdf");
		assertThat(AttachmentStorageService.sanitizeOriginalFilename(" a\u0000b\r\n.txt ")).isEqualTo("ab.txt");
		assertThat(AttachmentStorageService.sanitizeOriginalFilename("notes.txt")).isEqualTo("notes.txt");
	}

	@Test
	void shouldTruncateLongFilenamesKeepingExtensionAndSurrogatePairs() {
		String exact = "a".repeat(AttachmentStorageService.MAX_ORIGINAL_FILENAME_LENGTH);
		assertThat(AttachmentStorageService.sanitizeOriginalFilename(exact)).isEqualTo(exact);

		String truncated = AttachmentStorageService.sanitizeOriginalFilename("b".repeat(300) + ".zip");
		assertThat(truncated).hasSize(AttachmentStorageService.MAX_ORIGINAL_FILENAME_LENGTH).endsWith(".zip");

		// 잘리는 위치가 surrogate pair 가운데면 pair 전체를 뺀다.
		String emoji = "\uD83D\uDE00";
		String surrogateBoundary = AttachmentStorageService.sanitizeOriginalFilename("c".repeat(250) + emoji + "d".repeat(10) + ".txt");
		assertThat(surrogateBoundary).isEqualTo("c".repeat(250) + ".txt");

		String unsafeExtension = AttachmentStorageService.sanitizeOriginalFilename("e".repeat(300) + ".확장자");
		assertThat(unsafeExtension).hasSize(AttachmentStorageService.MAX_ORIGINAL_FILENAME_LENGTH).startsWith("eee");
	}

	@Test
	void shouldKeepValidContentTypesAndReplaceUnusableOnes() {
		assertThat(AttachmentStorageService.normalizeContentType(null)).isNull();
		assertThat(AttachmentStorageService.normalizeContentType(" ")).isNull();
		assertThat(AttachmentStorageService.normalizeContentType("text/plain; charset=UTF-8")).isEqualTo("text/plain; charset=UTF-8");
		assertThat(AttachmentStorageService.normalizeContentType("application/zip")).isEqualTo("application/zip");
		assertThat(AttachmentStorageService.normalizeContentType("not a media type")).isEqualTo("application/octet-stream");
		assertThat(AttachmentStorageService.normalizeContentType("*/*")).isEqualTo("application/octet-stream");
		assertThat(AttachmentStorageService.normalizeContentType("text/*")).isEqualTo("application/octet-stream");
		assertThat(AttachmentStorageService.normalizeContentType("text/plain\u0000")).isEqualTo("application/octet-stream");
		assertThat(AttachmentStorageService.normalizeContentType("text/" + "x".repeat(300))).isEqualTo("application/octet-stream");
	}
}
