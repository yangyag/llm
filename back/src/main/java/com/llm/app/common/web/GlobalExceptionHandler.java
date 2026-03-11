package com.llm.app.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler({
		ConstraintViolationException.class,
		MethodArgumentNotValidException.class,
		MethodArgumentTypeMismatchException.class,
		HttpMessageNotReadableException.class
	})
	public org.springframework.http.ResponseEntity<ErrorResponse> handleInvalidRequest(
		Exception exception,
		HttpServletRequest request
	) {
		return buildResponse(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage(), request);
	}

	@ExceptionHandler(NoResourceFoundException.class)
	public org.springframework.http.ResponseEntity<ErrorResponse> handleNotFound(
		NoResourceFoundException exception,
		HttpServletRequest request
	) {
		return buildResponse(HttpStatus.NOT_FOUND, "NOT_FOUND", exception.getMessage(), request);
	}

	@ExceptionHandler(Exception.class)
	public org.springframework.http.ResponseEntity<ErrorResponse> handleUnexpected(
		Exception exception,
		HttpServletRequest request
	) {
		return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", exception.getMessage(), request);
	}

	private org.springframework.http.ResponseEntity<ErrorResponse> buildResponse(
		HttpStatus status,
		String code,
		String message,
		HttpServletRequest request
	) {
		ErrorResponse body = new ErrorResponse(code, message, Instant.now(), request.getRequestURI());
		return org.springframework.http.ResponseEntity.status(status).body(body);
	}
}
