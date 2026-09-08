package com.llm.app.auth.internal;

import com.llm.app.auth.api.UserRole;

public record AdminMeResponse(Long userId, String username, UserRole role) {}
