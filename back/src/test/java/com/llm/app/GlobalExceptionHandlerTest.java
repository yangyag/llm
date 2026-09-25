package com.llm.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletRequest;

class GlobalExceptionHandlerTest {
	private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

	@Test
	void databaseDataExceptionsShouldBeBadRequestNotConflict() {
		// PostgreSQL 22021(NUL 등 인코딩 불가 문자)·22001(길이 초과)은 입력 문제다.
		for (String sqlState : new String[] { "22021", "22001" }) {
			var response = handler.handleConflict(
				new DataIntegrityViolationException("could not execute statement",
					new RuntimeException("wrapped", new SQLException("invalid byte sequence", sqlState))),
				new MockHttpServletRequest("POST", "/api/v1/posts"));
			assertThat(response.getStatusCode().value()).isEqualTo(400);
			assertThat(response.getBody().code()).isEqualTo("INVALID_REQUEST");
			assertThat(response.getBody().message()).doesNotContain("invalid byte sequence");
		}
	}

	@Test
	void constraintViolationsShouldStayConflict() {
		var response = handler.handleConflict(
			new DataIntegrityViolationException("duplicate key",
				new SQLException("duplicate key value violates unique constraint", "23505")),
			new MockHttpServletRequest("POST", "/api/v1/posts"));
		assertThat(response.getStatusCode().value()).isEqualTo(409);
		assertThat(response.getBody().code()).isEqualTo("CONFLICT");
	}
}
