package com.llm.app.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.llm.app.common.SecretKeyDerivation;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.util.Date;
import javax.crypto.SecretKey;
import java.util.Optional;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;

class JwtProviderTest {

	private static final String SECRET = "testSecretKeyForUnitTestsOnlyMustBeAtLeast256BitsLong!!";

	@Test
	void generateAndValidateShouldRoundTripUserId() {
		JwtProvider provider = provider(SECRET, 3600000);
		String token = provider.generateToken("member1");
		assertThat(provider.validateAndGetUserId(token)).isEqualTo(1L);
		assertThat(provider.authenticate("Bearer " + token)).isEqualTo(1L);
	}

	@Test
	void expiredTokenShouldBeRejected() {
		JwtProvider provider = provider(SECRET, -1000);
		String token = provider.generateToken("member1");
		assertThatThrownBy(() -> provider.validateAndGetUserId(token))
			.isInstanceOf(InvalidCredentialsException.class);
	}

	@Test
	void tokenSignedWithDifferentSecretShouldBeRejected() {
		JwtProvider provider = provider(SECRET, 3600000);
		JwtProvider otherProvider = provider("anotherSecretForWrongKeySimulationMustBeAtLeast256Bits!!", 3600000);
		String token = otherProvider.generateToken("member1");
		assertThatThrownBy(() -> provider.validateAndGetUserId(token))
			.isInstanceOf(InvalidCredentialsException.class);
	}

	@Test
	void tamperedTokenShouldBeRejected() {
		JwtProvider provider = provider(SECRET, 3600000);
		String token = provider.generateToken("member1");
		String tampered = token.substring(0, token.length() - 3) + "abc";
		assertThatThrownBy(() -> provider.validateAndGetUserId(tampered))
			.isInstanceOf(InvalidCredentialsException.class);
	}

	@Test
	void authenticateShouldRequireBearerPrefix() {
		JwtProvider provider = provider(SECRET, 3600000);
		String token = provider.generateToken("member1");
		assertThatThrownBy(() -> provider.authenticate(null))
			.isInstanceOf(InvalidCredentialsException.class);
		assertThatThrownBy(() -> provider.authenticate(""))
			.isInstanceOf(InvalidCredentialsException.class);
		assertThatThrownBy(() -> provider.authenticate("Token " + token))
			.isInstanceOf(InvalidCredentialsException.class);
	}

	@Test
	void shortSecretShouldStillProduceValidToken() {
		// 프로덕션에서도 SecretKeyDerivation으로 32바이트로 확장되므로 짧은 secret도 동작해야 한다.
		JwtProvider provider = provider("short", 3600000);
		String token = provider.generateToken("admin");
		assertThat(provider.validateAndGetUserId(token)).isEqualTo(1L);
	}

	@Test
	void tokenShouldContainConfiguredExpiration() {
		SecretKey key = Keys.hmacShaKeyFor(SecretKeyDerivation.derive32Bytes(SECRET));
		long issuedAt = 1_000_000L;
		long expirationMs = 60_000L;
		JwtProvider provider = provider(SECRET, expirationMs);
		String token = provider.generateToken("member1");
		Date exp = Jwts.parser()
			.verifyWith(key)
			.build()
			.parseSignedClaims(token)
			.getPayload()
			.getExpiration();
		Date iat = Jwts.parser()
			.verifyWith(key)
			.build()
			.parseSignedClaims(token)
			.getPayload()
			.getIssuedAt();
		assertThat(exp.getTime() - iat.getTime()).isEqualTo(expirationMs);
	}

	private JwtProvider provider(String secret, long expirationMs) {
		AdminRepository repository = mock(AdminRepository.class);
		Admin user = mock(Admin.class);
		when(user.getId()).thenReturn(1L);
		when(repository.findByUsername(anyString())).thenReturn(Optional.of(user));
		when(repository.existsById(1L)).thenReturn(true);
		return new JwtProvider(secret, expirationMs, repository);
	}

	@Test
	void loginMustBindTokenToTheAccountWhosePasswordWasChecked() {
		AdminRepository repository = mock(AdminRepository.class);
		Admin verified = mock(Admin.class);
		Admin replacement = mock(Admin.class);
		when(verified.getId()).thenReturn(1L);
		when(verified.getUsername()).thenReturn("reused");
		when(verified.getRole()).thenReturn(UserRole.USER);
		when(replacement.getId()).thenReturn(2L);
		when(repository.findByUsername("reused")).thenReturn(Optional.of(verified), Optional.of(replacement));
		var encoder = mock(org.springframework.security.crypto.password.PasswordEncoder.class);
		when(encoder.matches(any(), any())).thenReturn(true);
		var provider = new JwtProvider(SECRET, 3600000, repository);
		var response = new AuthService(repository, encoder, provider).login(new LoginRequest("reused", "test-input"));
		var key = Keys.hmacShaKeyFor(SecretKeyDerivation.derive32Bytes(SECRET));
		assertThat(Jwts.parser().verifyWith(key).build().parseSignedClaims(response.token()).getPayload().getSubject())
			.isEqualTo("1");
	}

	@Test
	void legacyTokenMustBeRejectedEvenWhenUsernameIsNumeric() {
		SecretKey key = Keys.hmacShaKeyFor(SecretKeyDerivation.derive32Bytes(SECRET));
		String legacyToken = Jwts.builder().subject("1")
			.expiration(new Date(System.currentTimeMillis() + 60000)).signWith(key).compact();
		assertThatThrownBy(() -> provider(SECRET, 3600000).authenticate("Bearer " + legacyToken))
			.isInstanceOf(InvalidCredentialsException.class);
	}
}
