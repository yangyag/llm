package com.llm.app.auth.internal;

import com.llm.app.auth.api.InvalidCredentialsException;
import com.llm.app.auth.exception.TooManyLoginAttemptsException;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final AdminRepository adminRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final LoginAttemptLimiter loginAttemptLimiter;
    // 없는 아이디도 같은 비용의 해시 비교를 거쳐 응답 시간으로 아이디 존재 여부를 알 수 없게 한다.
    private final String unknownUserPasswordHash;

    /**
     * 인증 서비스에 필요한 저장소와 인증 구성 요소를 초기화한다.
     *
     * @param adminRepository 관리자 계정 저장소
     * @param passwordEncoder 비밀번호 해시 검증기
     * @param jwtProvider JWT 발급기
     * @param loginAttemptLimiter 클라이언트 IP별 로그인 시도 제한기
     */
    public AuthService(
        AdminRepository adminRepository,
        PasswordEncoder passwordEncoder,
        JwtProvider jwtProvider,
        LoginAttemptLimiter loginAttemptLimiter
    ) {
        this.adminRepository = adminRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtProvider = jwtProvider;
        this.loginAttemptLimiter = loginAttemptLimiter;
        this.unknownUserPasswordHash = passwordEncoder.encode("unknown-user-timing-guard");
    }

    /**
     * 사용자 이름과 비밀번호를 검증하고 인증 토큰을 발급한다.
     *
     * @param request 사용자 이름과 비밀번호를 담은 로그인 요청
     * @param clientAddress 시도 횟수를 셀 클라이언트 IP
     * @return 발급된 토큰과 계정 정보를 담은 로그인 응답
     * @throws TooManyLoginAttemptsException 이 IP의 로그인 시도 한도를 넘었을 때
     * @throws InvalidCredentialsException 사용자 이름이나 비밀번호가 올바르지 않을 때
     */
    public LoginResponse login(LoginRequest request, String clientAddress) {
        loginAttemptLimiter.acquire(clientAddress);
        Admin admin = adminRepository.findByUsername(request.username()).orElse(null);
        String passwordHash = admin != null ? admin.getPasswordHash() : unknownUserPasswordHash;
        boolean passwordMatches = passwordEncoder.matches(request.password(), passwordHash);
        if (admin == null || !passwordMatches) {
            throw new InvalidCredentialsException("Invalid username or password");
        }

        loginAttemptLimiter.reset(clientAddress);
        String token = jwtProvider.generateToken(admin);
        return new LoginResponse(token, admin.getId(), admin.getUsername(), admin.getRole());
    }

    /**
     * 검증된 계정 ID로 현재 계정 정보를 조회한다.
     *
     * @param userId 검증된 계정 ID
     * @return 계정 ID, 사용자 이름, 권한을 담은 현재 사용자 응답
     * @throws InvalidCredentialsException 계정이 존재하지 않거나 삭제된 경우
     */
    public AdminMeResponse me(Long userId) {
        Admin admin = adminRepository.findById(userId)
            .orElseThrow(() -> new InvalidCredentialsException("User no longer exists"));
        return new AdminMeResponse(admin.getId(), admin.getUsername(), admin.getRole());
    }
}
