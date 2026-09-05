package com.llm.app.auth;

public record LoginResponse(String token, Long userId, String username, UserRole role) {}
