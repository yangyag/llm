package com.llm.app.auth.api;

public record AuthenticatedUser(Long userId, String username, UserRole role) {}
