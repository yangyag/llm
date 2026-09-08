package com.llm.app.auth.exception;

public class SelfDeleteNotAllowedException extends RuntimeException {
    public SelfDeleteNotAllowedException(String message) {
        super(message);
    }
}
