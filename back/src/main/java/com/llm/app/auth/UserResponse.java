package com.llm.app.auth;

import java.time.Instant;

public record UserResponse(Long id, String username, UserRole role, Instant createdAt) {}
