package com.llm.app.auth;

import com.llm.app.common.SecretKeyDerivation;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtProvider {

    private final SecretKey secretKey;
    private final long expirationMs;
    private final AdminRepository adminRepository;

    public JwtProvider(
        @Value("${app.jwt.secret}") String secret,
        @Value("${app.jwt.expiration-ms:3600000}") long expirationMs,
        AdminRepository adminRepository
    ) {
        this.secretKey = Keys.hmacShaKeyFor(SecretKeyDerivation.derive32Bytes(secret));
        this.expirationMs = expirationMs;
        this.adminRepository = adminRepository;
    }

    public Long authenticate(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new InvalidCredentialsException("Authentication required");
        }
        return validateAndGetUserId(authHeader.substring(7));
    }

    public String generateToken(String username) {
        Admin user = adminRepository.findByUsername(username)
            .orElseThrow(() -> new InvalidCredentialsException("User no longer exists"));
        return generateToken(user);
    }

    public String generateToken(Admin user) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
            .subject(user.getId().toString())
            .claim("tokenVersion", 2)
            .issuedAt(new Date(now))
            .expiration(new Date(now + expirationMs))
            .signWith(secretKey)
            .compact();
    }

    public Long validateAndGetUserId(String token) {
        try {
            var claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
            if (!Integer.valueOf(2).equals(claims.get("tokenVersion", Integer.class))) {
                throw new InvalidCredentialsException("Please log in again");
            }
            long userId = Long.parseLong(claims.getSubject());
            if (userId <= 0 || !adminRepository.existsById(userId)) {
                throw new InvalidCredentialsException("User no longer exists");
            }
            return userId;
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidCredentialsException("Invalid or expired token");
        }
    }
}
