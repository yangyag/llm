package com.llm.app.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.llm.app.auth.exception.TooManyLoginAttemptsException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LoginAttemptLimiterTest {
	private final MutableClock clock = new MutableClock(Instant.parse("2026-09-25T00:00:00Z"));
	private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(3, Duration.ofMinutes(15), clock);

	@Test
	void shouldBlockAfterMaxAttemptsUntilWindowEnds() {
		for (int attempt = 0; attempt < 3; attempt++) {
			limiter.acquire("203.0.113.10");
		}
		clock.advance(Duration.ofMinutes(5));

		assertThatThrownBy(() -> limiter.acquire("203.0.113.10"))
			.isInstanceOfSatisfying(TooManyLoginAttemptsException.class,
				exception -> assertThat(exception.getRetryAfterSeconds()).isEqualTo(600));
		// 차단 중인 시도는 구간을 늘리지 않는다.
		assertThatThrownBy(() -> limiter.acquire("203.0.113.10")).isInstanceOf(TooManyLoginAttemptsException.class);

		clock.advance(Duration.ofMinutes(10));
		assertThatCode(() -> limiter.acquire("203.0.113.10")).doesNotThrowAnyException();
	}

	@Test
	void shouldCountEachClientSeparatelyAndResetOnSuccess() {
		for (int attempt = 0; attempt < 3; attempt++) {
			limiter.acquire("203.0.113.10");
		}
		assertThatCode(() -> limiter.acquire("203.0.113.11")).doesNotThrowAnyException();

		limiter.reset("203.0.113.11");
		for (int attempt = 0; attempt < 3; attempt++) {
			limiter.acquire("203.0.113.11");
		}
		assertThatThrownBy(() -> limiter.acquire("203.0.113.11")).isInstanceOf(TooManyLoginAttemptsException.class);
		assertThatThrownBy(() -> limiter.acquire("203.0.113.10")).isInstanceOf(TooManyLoginAttemptsException.class);
	}

	@Test
	void shouldPurgeExpiredClientsWhenTrackingLimitIsReached() {
		for (int client = 0; client < LoginAttemptLimiter.MAX_TRACKED_CLIENTS; client++) {
			limiter.acquire("10.0." + (client / 256) + "." + (client % 256));
		}
		clock.advance(Duration.ofMinutes(15));

		for (int attempt = 0; attempt < 3; attempt++) {
			limiter.acquire("203.0.113.20");
		}
		assertThatThrownBy(() -> limiter.acquire("203.0.113.20")).isInstanceOf(TooManyLoginAttemptsException.class);
	}

	@Test
	void shouldRejectNonPositiveSettings() {
		assertThatThrownBy(() -> new LoginAttemptLimiter(0, Duration.ofMinutes(1)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new LoginAttemptLimiter(1, Duration.ZERO))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private static final class MutableClock extends Clock {
		private Instant now;

		private MutableClock(Instant now) {
			this.now = now;
		}

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}
}
