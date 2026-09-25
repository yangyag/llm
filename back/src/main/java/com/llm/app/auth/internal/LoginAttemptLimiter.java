package com.llm.app.auth.internal;

import com.llm.app.auth.exception.TooManyLoginAttemptsException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 클라이언트 IP별 로그인 시도 횟수를 제한한다.
 * 계정별로 잠그면 남이 일부러 틀려서 관리자 로그인을 막을 수 있으므로 IP 단위로만 센다.
 * 상태는 프로세스 메모리에만 있으므로 백엔드를 재시작하면 초기화된다.
 */
@Component
public class LoginAttemptLimiter {

    /** 추적하는 IP 수 상한. 넘으면 만료된 기록을 정리하고, 그래도 넘으면 새 IP는 세지 않는다. */
    static final int MAX_TRACKED_CLIENTS = 10_000;

    private final int maxAttempts;
    private final Duration window;
    private final Clock clock;
    private final ConcurrentHashMap<String, Attempts> attempts = new ConcurrentHashMap<>();

    /**
     * 설정값으로 로그인 시도 제한기를 초기화한다.
     *
     * @param maxAttempts 한 구간에서 허용하는 실패 가능 시도 수
     * @param window 시도 횟수를 세는 구간 길이
     */
    @Autowired
    public LoginAttemptLimiter(
        @Value("${app.auth.login-attempts.max-failures:10}") int maxAttempts,
        @Value("${app.auth.login-attempts.window:15m}") Duration window
    ) {
        this(maxAttempts, window, Clock.systemUTC());
    }

    LoginAttemptLimiter(int maxAttempts, Duration window, Clock clock) {
        if (maxAttempts <= 0 || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("login attempt limit and window must be positive");
        }
        this.maxAttempts = maxAttempts;
        this.window = window;
        this.clock = clock;
    }

    /**
     * 비밀번호를 확인하기 전에 시도 하나를 먼저 센다.
     * 병렬 요청이 확인 결과를 기다리는 동안 한도를 넘어 시도하지 못하게 결과보다 먼저 센다.
     *
     * @param clientAddress 요청한 클라이언트 IP
     * @throws TooManyLoginAttemptsException 현재 구간의 시도 한도를 이미 다 쓴 경우
     */
    public void acquire(String clientAddress) {
        Instant now = clock.instant();
        if (attempts.size() >= MAX_TRACKED_CLIENTS && !attempts.containsKey(clientAddress)) {
            attempts.values().removeIf(entry -> entry.isExpired(now, window));
            if (attempts.size() >= MAX_TRACKED_CLIENTS) {
                return;
            }
        }
        Attempts updated = attempts.compute(clientAddress, (key, current) ->
            current == null || current.isExpired(now, window)
                ? new Attempts(now, 1)
                : new Attempts(current.windowStart(), Math.min(current.count() + 1, maxAttempts + 1)));
        if (updated.count() > maxAttempts) {
            Duration retryAfter = Duration.between(now, updated.windowStart().plus(window));
            throw new TooManyLoginAttemptsException(Math.max(1, (retryAfter.toMillis() + 999) / 1000));
        }
    }

    /**
     * 로그인에 성공한 IP의 시도 기록을 지운다.
     *
     * @param clientAddress 로그인에 성공한 클라이언트 IP
     */
    public void reset(String clientAddress) {
        attempts.remove(clientAddress);
    }

    private record Attempts(Instant windowStart, int count) {
        boolean isExpired(Instant now, Duration window) {
            return !now.isBefore(windowStart.plus(window));
        }
    }
}
