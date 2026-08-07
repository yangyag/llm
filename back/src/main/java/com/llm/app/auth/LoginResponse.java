package com.llm.app.auth;

public record LoginResponse(String token, String username, UserRole role) {}
