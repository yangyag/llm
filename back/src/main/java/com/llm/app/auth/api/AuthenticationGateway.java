package com.llm.app.auth.api;

public interface AuthenticationGateway {
    Long authenticate(String authHeader);
}
