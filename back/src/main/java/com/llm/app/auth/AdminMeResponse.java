package com.llm.app.auth;

public record AdminMeResponse(Long userId, String username, UserRole role) {}
