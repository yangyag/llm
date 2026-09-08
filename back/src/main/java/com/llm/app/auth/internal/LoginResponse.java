package com.llm.app.auth.internal;

import com.llm.app.auth.api.UserRole;

public record LoginResponse(String token, Long userId, String username, UserRole role) {}
