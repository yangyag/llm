package com.llm.app.auth.api;

public interface IdentityAccess {
    AuthenticatedUser requireUser(Long userId);
}
