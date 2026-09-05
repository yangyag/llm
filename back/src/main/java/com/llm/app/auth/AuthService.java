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

        String token = jwtProvider.generateToken(admin);
        return new LoginResponse(token, admin.getId(), admin.getUsername(), admin.getRole());
    }

    /** 검증된 계정 ID 기준 현재 정보. 계정이 삭제됐으면 401로 거부한다. */
    public AdminMeResponse me(Long userId) {
        Admin admin = adminRepository.findById(userId)
            .orElseThrow(() -> new InvalidCredentialsException("User no longer exists"));
        return new AdminMeResponse(admin.getId(), admin.getUsername(), admin.getRole());
    }
}
