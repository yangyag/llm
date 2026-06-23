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

    public JwtProvider(
        @Value("${app.jwt.secret}") String secret,
        @Value("${app.jwt.expiration-ms:3600000}") long expirationMs
    ) {
        this.secretKey = Keys.hmacShaKeyFor(SecretKeyDerivation.derive32Bytes(secret));
        this.expirationMs = expirationMs;
    }

    public String authenticate(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new InvalidCredentialsException("Authentication required");
        }
        return validateAndGetUsername(authHeader.substring(7));
    }

    public String generateToken(String username) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
            .subject(username)
            .issuedAt(new Date(now))
            .expiration(new Date(now + expirationMs))
            .signWith(secretKey)
            .compact();
    }

    public String validateAndGetUsername(String token) {
        try {
            return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidCredentialsException("Invalid or expired token");
        }
    }
}
