package com.llm.app.auth;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final AdminRepository adminRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;

    public AuthService(AdminRepository adminRepository, PasswordEncoder passwordEncoder, JwtProvider jwtProvider) {
        this.adminRepository = adminRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtProvider = jwtProvider;
    }

    public LoginResponse login(LoginRequest request) {
        Admin admin = adminRepository.findByUsername(request.username())
            .orElseThrow(() -> new InvalidCredentialsException("Invalid username or password"));

        if (!passwordEncoder.matches(request.password(), admin.getPasswordHash())) {
            throw new InvalidCredentialsException("Invalid username or password");
        }

        String token = jwtProvider.generateToken(admin.getUsername());
        return new LoginResponse(token, admin.getUsername(), admin.getRole());
    }

    /** 토큰 username 기준 현재 계정 정보. 계정이 삭제됐으면 401로 처리되게 InvalidCredentials. */
    public AdminMeResponse me(String username) {
        Admin admin = adminRepository.findByUsername(username)
            .orElseThrow(() -> new InvalidCredentialsException("User no longer exists"));
        return new AdminMeResponse(admin.getUsername(), admin.getRole());
    }
}
