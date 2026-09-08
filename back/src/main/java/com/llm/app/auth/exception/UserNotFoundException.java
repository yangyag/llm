package com.llm.app.auth.exception;

public class UserNotFoundException extends RuntimeException {
    private UserNotFoundException(String message) {
        super(message);
    }

    public static UserNotFoundException user(Long id) {
        return new UserNotFoundException("user not found: " + id);
    }
}
