package com.llm.app.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llm.app.auth.api.UserRole;
import com.llm.app.auth.internal.Admin;
import com.llm.app.auth.internal.AdminRepository;
import com.llm.app.auth.internal.JwtProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * MockMvc를 거치지 않는 실제 내장 Tomcat에서 요청 파싱 단계의 한도와 오류 응답을 확인한다.
 * multipart 텍스트 필드 한도(maxPostSize)는 Tomcat이 적용하므로 MockMvc로는 재현되지 않는다.
 */
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = {
		"spring.datasource.url=jdbc:h2:mem:http-limits;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
		"spring.servlet.multipart.max-file-size=100MB",
		"spring.servlet.multipart.max-request-size=500MB"
	}
)
class HttpRequestLimitsTest {
	private static final String BOUNDARY = "----httpRequestLimitsBoundary";

	@LocalServerPort
	private int port;

	@Autowired
	private AdminRepository adminRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JwtProvider jwtProvider;

	@Autowired
	private ObjectMapper objectMapper;

	private final HttpClient client = HttpClient.newHttpClient();
	private String token;

	@BeforeEach
	void setUp() {
		adminRepository.deleteAll();
		Admin admin = new Admin();
		admin.setUsername("limitsadmin");
		admin.setPasswordHash(passwordEncoder.encode("limitspass"));
		admin.setRole(UserRole.ADMIN);
		admin.setCreatedAt(Instant.now());
		adminRepository.saveAndFlush(admin);
		token = jwtProvider.generateToken("limitsadmin");
	}

	@Test
	void unsupportedMethodReturns405WithAllowHeader() throws Exception {
		HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/api/v1/posts")).DELETE());

		assertThat(response.statusCode()).isEqualTo(405);
		assertThat(json(response).path("code").asText()).isEqualTo("METHOD_NOT_ALLOWED");
		assertThat(response.headers().firstValue("Allow")).hasValueSatisfying(allow -> assertThat(allow).contains("GET"));
	}

	@Test
	void unsupportedContentTypeReturns415() throws Exception {
		HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/api/v1/posts"))
			.header("Authorization", "Bearer " + token)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString("{}")));

		assertThat(response.statusCode()).isEqualTo(415);
		assertThat(json(response).path("code").asText()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
	}

	@Test
	void malformedMultipartReturns400() throws Exception {
		HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/api/v1/posts"))
			.header("Authorization", "Bearer " + token)
			.header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
			.POST(HttpRequest.BodyPublishers.ofString("this is not a multipart body")));

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(json(response).path("code").asText()).isEqualTo("INVALID_REQUEST");
	}

	@Test
	void richDocumentLargerThanTomcatDefaultFormLimitIsAccepted() throws Exception {
		// 90만 자(한글) 문서: 평문·노드 수·decode 크기는 코덱 한도 안이고, Base64는 Tomcat 기본 2MB를 넘는다.
		String paragraphText = "가".repeat(1000);
		StringBuilder document = new StringBuilder("{\"type\":\"doc\",\"content\":[");
		for (int index = 0; index < 900; index++) {
			if (index > 0) {
				document.append(',');
			}
			document.append("{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"")
				.append(paragraphText)
				.append("\"}]}");
		}
		document.append("]}");
		String documentBase64 = Base64.getEncoder()
			.encodeToString(document.toString().getBytes(StandardCharsets.UTF_8));
		assertThat(documentBase64.length()).isGreaterThan(2 * 1024 * 1024);

		HttpResponse<String> response = postMultipart(Map.of(
			"title", "long rich body",
			"bodyFormat", "TIPTAP_JSON",
			"bodyDocumentBase64", documentBase64
		));

		assertThat(response.statusCode()).isEqualTo(201);
		assertThat(json(response).path("body").asText()).hasSize(900 * 1000 + 899);
	}

	@Test
	void textFieldsBeyondFormLimitReturn413() throws Exception {
		HttpResponse<String> response = postMultipart(Map.of(
			"title", "too large",
			"bodyBase64", "A".repeat(9 * 1024 * 1024)
		));

		assertThat(response.statusCode()).isEqualTo(413);
		assertThat(json(response).path("code").asText()).isEqualTo("ATTACHMENT_TOO_LARGE");
	}

	private HttpResponse<String> postMultipart(Map<String, String> fields) throws Exception {
		StringBuilder body = new StringBuilder();
		for (Map.Entry<String, String> field : new LinkedHashMap<>(fields).entrySet()) {
			body.append("--").append(BOUNDARY).append("\r\n")
				.append("Content-Disposition: form-data; name=\"").append(field.getKey()).append("\"\r\n\r\n")
				.append(field.getValue()).append("\r\n");
		}
		body.append("--").append(BOUNDARY).append("--\r\n");
		return send(HttpRequest.newBuilder(uri("/api/v1/posts"))
			.header("Authorization", "Bearer " + token)
			.header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
			.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)));
	}

	private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
		return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private URI uri(String path) {
		return URI.create("http://127.0.0.1:" + port + path);
	}

	private JsonNode json(HttpResponse<String> response) throws Exception {
		return objectMapper.readTree(response.body());
	}
}
