package com.llm.app.auth;

public class SelfDeleteNotAllowedException extends RuntimeException {
    public SelfDeleteNotAllowedException(String message) {
        super(message);
    }
}
