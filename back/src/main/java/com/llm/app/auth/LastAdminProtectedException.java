package com.llm.app.auth;

public class LastAdminProtectedException extends RuntimeException {
    public LastAdminProtectedException(String message) {
        super(message);
    }
}
