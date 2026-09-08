package com.llm.app.auth.internal;

import com.llm.app.auth.api.UserRole;

import java.time.Instant;

public record UserResponse(Long id, String username, UserRole role, Instant createdAt) {}
