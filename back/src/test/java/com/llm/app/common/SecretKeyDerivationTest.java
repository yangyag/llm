package com.llm.app.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SecretKeyDerivationTest {

	@Test
	void shortSecretShouldBeStretchedTo32Bytes() {
		byte[] key = SecretKeyDerivation.derive32Bytes("short");
		assertThat(key).hasSize(32);
		assertThat(key).isEqualTo(SecretKeyDerivation.derive32Bytes("short"));
	}

	@Test
	void exactly32ByteSecretShouldBeKept() {
		String secret = "a".repeat(32);
		byte[] key = SecretKeyDerivation.derive32Bytes(secret);
		assertThat(key).hasSize(32);
		assertThat(key).isEqualTo(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	@Test
	void longSecretShouldBeTruncatedToFirst32Bytes() {
		String secret = "b".repeat(64);
		byte[] key = SecretKeyDerivation.derive32Bytes(secret);
		assertThat(key).hasSize(32);
		byte[] first32 = new byte[32];
		System.arraycopy(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), 0, first32, 0, 32);
		assertThat(key).isEqualTo(first32);
	}

	@Test
	void differentSecretsShouldProduceDifferentKeys() {
		assertThat(Arrays.equals(
			SecretKeyDerivation.derive32Bytes("secret-a"),
			SecretKeyDerivation.derive32Bytes("secret-b")
		)).isFalse();
	}

	@Test
	void unicodeSecretShouldStillProduce32Bytes() {
		byte[] key = SecretKeyDerivation.derive32Bytes("한글비밀키입니다가나다라마바사아자차카타파하");
		assertThat(key).hasSize(32);
	}
}
