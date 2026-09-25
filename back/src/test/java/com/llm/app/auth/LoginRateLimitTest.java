package com.llm.app.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llm.app.auth.api.UserRole;
import com.llm.app.auth.internal.Admin;
import com.llm.app.auth.internal.AdminRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 실제 내장 Tomcat에서 로그인 시도 제한과 X-Forwarded-For 기반 클라이언트 IP 해석을 확인한다.
 * RemoteIpValve는 MockMvc에서 동작하지 않으므로 실제 HTTP 요청으로 검증한다.
 */
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = {
		"spring.datasource.url=jdbc:h2:mem:login-limit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
		"app.auth.login-attempts.max-failures=3"
	}
)
class LoginRateLimitTest {
	// 운영 요청 모양: 실제 클라이언트 주소 뒤에 llm-front nginx가 본 호스트 nginx(docker gateway) 주소가 붙는다.
	private static final String GATEWAY = "172.18.0.1";

	@LocalServerPort
	private int port;

	@Autowired
	private AdminRepository adminRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private ObjectMapper objectMapper;

	private final HttpClient client = HttpClient.newHttpClient();

	@BeforeEach
	void setUp() {
		adminRepository.deleteAll();
		Admin user = new Admin();
		user.setUsername("limituser");
		user.setPasswordHash(passwordEncoder.encode("limitpass"));
		user.setRole(UserRole.USER);
		user.setCreatedAt(Instant.now());
		adminRepository.saveAndFlush(user);
	}

	@Test
	void failedLoginsShouldBeLimitedPerForwardedClientAddress() throws Exception {
		String client = "203.0.113.10, " + GATEWAY;
		for (int attempt = 0; attempt < 3; attempt++) {
			assertThat(login("limituser", "wrongpass", client).statusCode()).isEqualTo(401);
		}

		HttpResponse<String> blocked = login("limituser", "limitpass", client);
		assertThat(blocked.statusCode()).isEqualTo(429);
		assertThat(json(blocked).path("code").asText()).isEqualTo("TOO_MANY_LOGIN_ATTEMPTS");
		assertThat(blocked.headers().firstValue("Retry-After"))
			.hasValueSatisfying(seconds -> assertThat(Long.parseLong(seconds)).isBetween(1L, 900L));

		// 클라이언트가 앞쪽에 넣은 위조 주소는 무시되고 nginx가 붙인 실제 주소로 센다.
		assertThat(login("limituser", "limitpass", "198.51.100.7, 203.0.113.10, " + GATEWAY).statusCode())
			.isEqualTo(429);
		assertThat(login("limituser", "limitpass", "203.0.113.11, " + GATEWAY).statusCode()).isEqualTo(200);
	}

	@Test
	void successfulLoginShouldResetTheClientCount() throws Exception {
		String client = "203.0.113.30, " + GATEWAY;
		assertThat(login("limituser", "wrongpass", client).statusCode()).isEqualTo(401);
		assertThat(login("limituser", "wrongpass", client).statusCode()).isEqualTo(401);
		assertThat(login("limituser", "limitpass", client).statusCode()).isEqualTo(200);

		for (int attempt = 0; attempt < 3; attempt++) {
			assertThat(login("limituser", "wrongpass", client).statusCode()).isEqualTo(401);
		}
		assertThat(login("limituser", "wrongpass", client).statusCode()).isEqualTo(429);
	}

	@Test
	void unknownUsernameShouldCountAndLookLikeWrongPassword() throws Exception {
		String client = "203.0.113.40, " + GATEWAY;
		HttpResponse<String> unknown = login("nosuchuser", "wrongpass", client);
		HttpResponse<String> wrongPassword = login("limituser", "wrongpass", client);

		assertThat(unknown.statusCode()).isEqualTo(401);
		assertThat(json(unknown).path("message").asText()).isEqualTo(json(wrongPassword).path("message").asText());
		assertThat(login("nosuchuser", "wrongpass", client).statusCode()).isEqualTo(401);
		assertThat(login("limituser", "limitpass", client).statusCode()).isEqualTo(429);
	}

	private HttpResponse<String> login(String username, String password, String forwardedFor) throws Exception {
		String body = objectMapper.writeValueAsString(java.util.Map.of("username", username, "password", password));
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
			.header("Content-Type", "application/json")
			.header("X-Forwarded-For", forwardedFor)
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build();
		return client.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private JsonNode json(HttpResponse<String> response) throws Exception {
		return objectMapper.readTree(response.body());
	}
}
