package com.llm.app.common.web;

import com.llm.app.board.exception.AiProviderNotConfiguredException;
import com.llm.app.board.exception.AiReplyGenerationException;
import com.llm.app.board.exception.AiReplyModificationNotAllowedException;
import com.llm.app.board.exception.AiReplyNotAllowedException;
import com.llm.app.board.exception.AttachmentStorageException;
import com.llm.app.board.exception.AttachmentTooLargeException;
import com.llm.app.board.exception.BoardAttachmentNotFoundException;
import com.llm.app.board.exception.BoardPostNotFoundException;
import com.llm.app.board.exception.BoardReplyNotFoundException;
import com.llm.app.board.exception.FileConversionLockedException;
import com.llm.app.board.exception.InvalidAttachmentRequestException;
import com.llm.app.board.exception.InvalidBoardPasswordException;
import com.llm.app.board.exception.InvalidAiProviderException;
import com.llm.app.board.exception.InvalidEncodedBodyException;
import com.llm.app.board.exception.InvalidFileConversionRequestException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler({ InvalidEncodedBodyException.class })
	public org.springframework.http.ResponseEntity<ErrorResponse> handleInvalidEncodedBody(
		RuntimeException exception,
		HttpServletRequest request
	) {
		return buildResponse(HttpStatus.BAD_REQUEST, "INVALID_ENCODED_BODY", exception.getMessage(), request);
	}

	@ExceptionHandler({ InvalidBoardPasswordException.class })
	public org.springframework.http.ResponseEntity<ErrorResponse> handleInvalidPassword(
		RuntimeException exception,
		HttpServletRequest request
	) {
		return buildResponse(HttpStatus.FORBIDDEN, "INVALID_PASSWORD", exception.getMessage(), request);
	}

	@ExceptionHandler({ FileConversionLockedException.class })
	public org.springframework.http.ResponseEntity<ErrorResponse> handleFileConversionLocked(
		RuntimeException exception,
		HttpServletRequest request
	) {
		return buildResponse(HttpStatus.FORBIDDEN, "FILE_CONVERSION_LOCKED", exception.getMessage(), request);
	}

	@ExceptionHandler({ AiReplyModificationNotAllowedException.class })
	public org.springframework.http.ResponseEntity<ErrorResponse> handleAiReplyLocked(
		RuntimeException exception,
		HttpServletRequest request
	) {
		return buildResponse(HttpStatus.FORBIDDEN, "AI_REPLY_LOCKED", exception.getMessage(), request);
	}

	@ExceptionHandler({ AiReplyNotAllowedException.class })
	public org.springframework.http.ResponseEntity<ErrorResponse> handleAiReplyNotAllowed(
		RuntimeException exception,
		HttpServletRequest request
	) {
		return buildResponse(HttpStatus.BAD_REQUEST, "AI_REPLY_NOT_ALLOWED", exception.getMessage(), request);
	}

	@ExceptionHandler({ InvalidAiProviderException.class })
	public org.springframework.http.ResponseEntity<ErrorResponse> handleInvalidAiProvider(
		RuntimeException exception,
		HttpServletRequest request
	) {
		return buildResponse(HttpStatus.BAD_REQUEST, "INVALID_AI_PROVIDER", exception.getMessage(), request);
	}

	@ExceptionHandler({ InvalidAttachmentRequestException.class })
	public org.springframework.http.ResponseEntity<ErrorResponse> handleInvalidAttachmentRequest(
		RuntimeException exception,
		HttpServletRequest request
	) {
		return buildResponse(HttpStatus.BAD_REQUEST, "INVALID_ATTACHMENT_REQUEST", exception.getMessage(), request);
	}

	@ExceptionHandler({ InvalidFileConversionRequestException.class })
	public org.springframework.http.ResponseEntity<ErrorResponse> handleInvalidFileConversionRequest(
		RuntimeException exception,
		HttpServletRequest request
	) {
		return buildResponse(HttpStatus.BAD_REQUEST, "INVALID_FILE_CONVERSION_REQUEST", exception.getMessage(), request);
	}

	@ExceptionHandler({ AiProviderNotConfiguredException.class })
	public org.springframework.http.ResponseEntity<ErrorResponse> handleAiProviderNotConfigured(
		RuntimeException exception,
		HttpServletRequest request
	) {
		return buildResponse(HttpStatus.SERVICE_UNAVAILABLE, "AI_PROVIDER_NOT_CONFIGURED", exception.getMessage(), request);
	}

	@ExceptionHandler({ AiReplyGenerationException.class })
	public org.springframework.http.ResponseEntity<ErrorResponse> handleAiReplyGeneration(
		RuntimeException exception,
		HttpServletRequest request
	) {
		return buildResponse(HttpStatus.BAD_GATEWAY, "AI_REPLY_GENERATION_FAILED", exception.getMessage(), request);
	}

	@ExceptionHandler({ BoardPostNotFoundException.class, BoardReplyNotFoundException.class, BoardAttachmentNotFoundException.class })
	public org.springframework.http.ResponseEntity<ErrorResponse> handleBoardNotFound(
		RuntimeException exception,
		HttpServletRequest request
	) {
		return buildResponse(HttpStatus.NOT_FOUND, "NOT_FOUND", exception.getMessage(), request);
	}

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	public org.springframework.http.ResponseEntity<ErrorResponse> handleAttachmentTooLarge(
		MaxUploadSizeExceededException exception,
		HttpServletRequest request
	) {
		return buildResponse(HttpStatus.PAYLOAD_TOO_LARGE, "ATTACHMENT_TOO_LARGE", exception.getMessage(), request);
	}

	@ExceptionHandler(AttachmentTooLargeException.class)
	public org.springframework.http.ResponseEntity<ErrorResponse> handleAttachmentTooLarge(
		AttachmentTooLargeException exception,
		HttpServletRequest request
	) {
		return buildResponse(HttpStatus.PAYLOAD_TOO_LARGE, "ATTACHMENT_TOO_LARGE", exception.getMessage(), request);
	}

	@ExceptionHandler(AttachmentStorageException.class)
	public org.springframework.http.ResponseEntity<ErrorResponse> handleAttachmentStorage(
		AttachmentStorageException exception,
		HttpServletRequest request
	) {
		return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "ATTACHMENT_STORAGE_ERROR", exception.getMessage(), request);
	}

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
