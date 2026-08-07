package com.llm.app.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.llm.app.common.SecretKeyDerivation;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.util.Date;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

class JwtProviderTest {

	private static final String SECRET = "testSecretKeyForUnitTestsOnlyMustBeAtLeast256BitsLong!!";

	@Test
	void generateAndValidateShouldRoundTripUsername() {
		JwtProvider provider = new JwtProvider(SECRET, 3600000);
		String token = provider.generateToken("member1");
		assertThat(provider.validateAndGetUsername(token)).isEqualTo("member1");
		assertThat(provider.authenticate("Bearer " + token)).isEqualTo("member1");
	}

	@Test
	void expiredTokenShouldBeRejected() {
		JwtProvider provider = new JwtProvider(SECRET, -1000);
		String token = provider.generateToken("member1");
		assertThatThrownBy(() -> provider.validateAndGetUsername(token))
			.isInstanceOf(InvalidCredentialsException.class);
	}

	@Test
	void tokenSignedWithDifferentSecretShouldBeRejected() {
		JwtProvider provider = new JwtProvider(SECRET, 3600000);
		JwtProvider otherProvider = new JwtProvider("anotherSecretForWrongKeySimulationMustBeAtLeast256Bits!!", 3600000);
		String token = otherProvider.generateToken("member1");
		assertThatThrownBy(() -> provider.validateAndGetUsername(token))
			.isInstanceOf(InvalidCredentialsException.class);
	}

	@Test
	void tamperedTokenShouldBeRejected() {
		JwtProvider provider = new JwtProvider(SECRET, 3600000);
		String token = provider.generateToken("member1");
		String tampered = token.substring(0, token.length() - 3) + "abc";
		assertThatThrownBy(() -> provider.validateAndGetUsername(tampered))
			.isInstanceOf(InvalidCredentialsException.class);
	}

	@Test
	void authenticateShouldRequireBearerPrefix() {
		JwtProvider provider = new JwtProvider(SECRET, 3600000);
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
		JwtProvider provider = new JwtProvider("short", 3600000);
		String token = provider.generateToken("admin");
		assertThat(provider.validateAndGetUsername(token)).isEqualTo("admin");
	}

	@Test
	void tokenShouldContainConfiguredExpiration() {
		SecretKey key = Keys.hmacShaKeyFor(SecretKeyDerivation.derive32Bytes(SECRET));
		long issuedAt = 1_000_000L;
		long expirationMs = 60_000L;
		JwtProvider provider = new JwtProvider(SECRET, expirationMs);
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
}
