package com.llm.app.auth.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final JwtProvider jwtProvider;

    public AuthController(AuthService authService, JwtProvider jwtProvider) {
        this.authService = authService;
        this.jwtProvider = jwtProvider;
    }

    // 운영에서는 nginx 두 단을 거치므로 remoteAddr는 server.forward-headers-strategy=native가
    // X-Forwarded-For에서 복원한 실제 클라이언트 IP다.
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        LoginResponse response = authService.login(request, httpRequest.getRemoteAddr());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/me")
    public ResponseEntity<AdminMeResponse> me(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        Long userId = jwtProvider.authenticate(authHeader);
        return ResponseEntity.ok(authService.me(userId));
    }
}
