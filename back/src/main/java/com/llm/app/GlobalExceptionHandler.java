package com.llm.app;

import com.llm.app.auth.exception.DuplicateUsernameException;
import com.llm.app.auth.exception.LastAdminProtectedException;
import com.llm.app.auth.exception.SelfDeleteNotAllowedException;
import com.llm.app.auth.exception.UserNotFoundException;
import com.llm.app.auth.api.ForbiddenException;
import com.llm.app.auth.api.InvalidCredentialsException;
import com.llm.app.common.web.ErrorResponse;
import com.llm.app.board.exception.AiProviderNotConfiguredException;
import com.llm.app.board.exception.AiReplyDisabledException;
import com.llm.app.board.exception.AiReplyGenerationException;
import com.llm.app.board.exception.AiReplyModificationNotAllowedException;
import com.llm.app.board.exception.AiReplyNotAllowedException;
import com.llm.app.board.exception.AttachmentStorageException;
import com.llm.app.board.exception.AttachmentTooLargeException;
import com.llm.app.board.exception.FileConversionLockedException;
import com.llm.app.board.exception.InvalidAttachmentRequestException;
import com.llm.app.board.exception.InvalidAiProviderException;
import com.llm.app.board.exception.InvalidEncodedBodyException;
import com.llm.app.board.exception.InvalidFileConversionRequestException;
import com.llm.app.board.exception.InvalidRichDocumentException;
import com.llm.app.board.exception.NotFoundException;
import com.llm.app.board.exception.RichTextClientRequiredException;
import com.llm.app.upload.exception.UploadSessionChunkTooLargeException;
import com.llm.app.upload.exception.InvalidUploadSessionRequestException;
import com.llm.app.upload.exception.UploadSessionNotFoundException;
import com.llm.app.upload.exception.UploadSessionStorageException;
import com.llm.app.upload.exception.UploadSessionStateException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(ForbiddenException.class)
	public org.springframework.http.ResponseEntity<ErrorResponse> handleForbidden(
		RuntimeException exception,
		HttpServletRequest request
	) {
		return buildResponse(HttpStatus.FORBIDDEN, "FORBIDDEN", exception.getMessage(), request);
	}

	@ExceptionHandler(DuplicateUsernameException.class)
	public org.springframework.http.ResponseEntity<ErrorResponse> handleDuplicateUsername(
		RuntimeException exception,
		HttpServletRequest request
	) {
		return buildResponse(HttpStatus.CONFLICT, "DUPLICATE_USERNAME", exception.getMessage(), request);
	}

	@ExceptionHandler(LastAdminProtectedException.class)
	public org.springframework.http.ResponseEntity<ErrorResponse> handleLastAdminProtected(
		RuntimeException exception,
		HttpServletRequest request
	) {
		return buildResponse(HttpStatus.CONFLICT, "LAST_ADMIN_PROTECTED", exception.getMessage(), request);
	}

	@ExceptionHandler(SelfDeleteNotAllowedException.class)
	public org.springframework.http.ResponseEntity<ErrorResponse> handleSelfDeleteNotAllowed(
		RuntimeException exception,
		HttpServletRequest request
	) {
		return buildResponse(HttpStatus.CONFLICT, "SELF_DELETE_NOT_ALLOWED", exception.getMessage(), request);
	}

	@ExceptionHandler({ InvalidCredentialsException.class })
	public org.springframework.http.ResponseEntity<ErrorResponse> handleInvalidCredentials(
		RuntimeException exception,
		HttpServletRequest request
	) {
		return buildResponse(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", exception.getMessage(), request);
	}

	@ExceptionHandler({ InvalidEncodedBodyException.class })
	public org.springframework.http.ResponseEntity<ErrorResponse> handleInvalidEncodedBody(
		RuntimeException exception,
		HttpServletRequest request
	) {
		return buildResponse(HttpStatus.BAD_REQUEST, "INVALID_ENCODED_BODY", exception.getMessage(), request);
	}

	@ExceptionHandler({ FileConversionLockedException.class })
	public org.springframework.http.ResponseEntity<ErrorResponse> handleFileConversionLocked(
		RuntimeException exception,
		HttpServletRequest request
	) {
		return buildResponse(HttpStatus.FORBIDDEN, "FILE_CONVERSION_LOCKED", exception.getMessage(), request);
	}

	// 2026-09-03 이후 미사용 — 댓글 AI 답변 기능 종료. 레거시 AI 답변 행 보호용으로 유지.
	@ExceptionHandler({ AiReplyModificationNotAllowedException.class })
	public org.springframework.http.ResponseEntity<ErrorResponse> handleAiReplyLocked(
		RuntimeException exception,
		HttpServletRequest request
	) {
		return buildResponse(HttpStatus.FORBIDDEN, "AI_REPLY_LOCKED", exception.getMessage(), request);
	}

	// 2026-09-03 이후 미사용 — 댓글 AI 답변 기능 종료. 레거시 AI 답변 행 보호용으로 유지.
	@ExceptionHandler({ AiReplyNotAllowedException.class })
	public org.springframework.http.ResponseEntity<ErrorResponse> handleAiReplyNotAllowed(
		RuntimeException exception,
		HttpServletRequest request
	) {
		return buildResponse(HttpStatus.BAD_REQUEST, "AI_REPLY_NOT_ALLOWED", exception.getMessage(), request);
	}

	// 2026-09-03 이후 미사용 — 댓글 AI 답변 기능 종료. 레거시 AI 답변 행 보호용으로 유지.
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

	@ExceptionHandler({ InvalidRichDocumentException.class })
	public org.springframework.http.ResponseEntity<ErrorResponse> handleInvalidRichDocument(
		RuntimeException exception,
		HttpServletRequest request
	) {
		return buildResponse(HttpStatus.BAD_REQUEST, "INVALID_RICH_DOCUMENT", exception.getMessage(), request);
	}

	@ExceptionHandler({ RichTextClientRequiredException.class })
	public org.springframework.http.ResponseEntity<ErrorResponse> handleRichTextClientRequired(
		RuntimeException exception,
		HttpServletRequest request
	) {
		return buildResponse(HttpStatus.CONFLICT, "RICH_TEXT_CLIENT_REQUIRED", exception.getMessage(), request);
	}

	@ExceptionHandler({ InvalidUploadSessionRequestException.class })
	public org.springframework.http.ResponseEntity<ErrorResponse> handleInvalidUploadSessionRequest(
		RuntimeException exception,
		HttpServletRequest request
	) {
		return buildResponse(HttpStatus.BAD_REQUEST, "INVALID_UPLOAD_SESSION_REQUEST", exception.getMessage(), request);
	}

	@ExceptionHandler({ UploadSessionStateException.class })
	public org.springframework.http.ResponseEntity<ErrorResponse> handleUploadSessionState(
		RuntimeException exception,
		HttpServletRequest request
	) {
		return buildResponse(HttpStatus.CONFLICT, "UPLOAD_SESSION_STATE_ERROR", exception.getMessage(), request);
	}

	// 2026-09-03 이후 미사용 — 댓글 AI 답변 기능 종료. 레거시 AI 답변 행 보호용으로 유지.
	@ExceptionHandler({ AiProviderNotConfiguredException.class })
	public org.springframework.http.ResponseEntity<ErrorResponse> handleAiProviderNotConfigured(
		RuntimeException exception,
		HttpServletRequest request
	) {
		return buildResponse(HttpStatus.SERVICE_UNAVAILABLE, "AI_PROVIDER_NOT_CONFIGURED", exception.getMessage(), request);
	}

	// 2026-09-03 이후 미사용 — 댓글 AI 답변 기능 종료. 레거시 AI 답변 행 보호용으로 유지.
	@ExceptionHandler({ AiReplyGenerationException.class })
	public org.springframework.http.ResponseEntity<ErrorResponse> handleAiReplyGeneration(
		RuntimeException exception,
		HttpServletRequest request
	) {
		return buildResponse(HttpStatus.BAD_GATEWAY, "AI_REPLY_GENERATION_FAILED", exception.getMessage(), request);
	}

	@ExceptionHandler({ AiReplyDisabledException.class })
	public org.springframework.http.ResponseEntity<ErrorResponse> handleAiReplyDisabled(
		RuntimeException exception,
		HttpServletRequest request
	) {
		return buildResponse(HttpStatus.GONE, "AI_REPLY_DISABLED", exception.getMessage(), request);
	}

	@ExceptionHandler({ NotFoundException.class, UserNotFoundException.class, UploadSessionNotFoundException.class })
	public org.springframework.http.ResponseEntity<ErrorResponse> handleBoardNotFound(
		RuntimeException exception,
		HttpServletRequest request
	) {
		return buildResponse(HttpStatus.NOT_FOUND, "NOT_FOUND", exception.getMessage(), request);
	}

	@ExceptionHandler({ MaxUploadSizeExceededException.class, AttachmentTooLargeException.class, UploadSessionChunkTooLargeException.class })
	public org.springframework.http.ResponseEntity<ErrorResponse> handleAttachmentTooLarge(
		Exception exception,
		HttpServletRequest request
	) {
		return buildResponse(HttpStatus.PAYLOAD_TOO_LARGE, "ATTACHMENT_TOO_LARGE", exception.getMessage(), request);
	}

	@ExceptionHandler({ AttachmentStorageException.class, UploadSessionStorageException.class })
	public org.springframework.http.ResponseEntity<ErrorResponse> handleAttachmentStorage(
		RuntimeException exception,
		HttpServletRequest request
	) {
		// 응답 메시지는 고정 문구라 원인 IOException은 로그로만 남긴다.
		log.error("Attachment storage failure on {}", request.getRequestURI(), exception);
		return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "ATTACHMENT_STORAGE_ERROR", exception.getMessage(), request);
	}

	@ExceptionHandler({ DataIntegrityViolationException.class, OptimisticLockingFailureException.class })
	public org.springframework.http.ResponseEntity<ErrorResponse> handleConflict(
		RuntimeException exception,
		HttpServletRequest request
	) {
		// Generic message on purpose: never echo raw SQL/constraint text to clients.
		return buildResponse(HttpStatus.CONFLICT, "CONFLICT", "request conflicts with the current resource state", request);
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

	// 크기 초과(MaxUploadSizeExceededException)는 위의 더 구체적인 처리기가 413으로 받는다.
	@ExceptionHandler(MultipartException.class)
	public org.springframework.http.ResponseEntity<ErrorResponse> handleMalformedMultipart(
		MultipartException exception,
		HttpServletRequest request
	) {
		return buildResponse(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "malformed multipart request", request);
	}

	@ExceptionHandler(Exception.class)
	public org.springframework.http.ResponseEntity<ErrorResponse> handleUnexpected(
		Exception exception,
		HttpServletRequest request
	) {
		// 405·415·파라미터 누락 등 Spring MVC 예외는 상태 코드를 스스로 알고 있다. 500으로 뭉개지 않는다.
		if (exception instanceof org.springframework.web.ErrorResponse frameworkError
			&& frameworkError.getStatusCode().is4xxClientError()) {
			return buildResponse(
				frameworkError.getStatusCode(),
				frameworkErrorCode(frameworkError.getStatusCode()),
				frameworkErrorMessage(frameworkError),
				frameworkError.getHeaders(),
				request
			);
		}
		// Generic message on purpose: raw exception text can carry SQL, paths or class names.
		log.error("Unexpected error on {} {}", request.getMethod(), request.getRequestURI(), exception);
		return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "unexpected server error", request);
	}

	private static String frameworkErrorCode(HttpStatusCode status) {
		return switch (status.value()) {
			case 404 -> "NOT_FOUND";
			case 405 -> "METHOD_NOT_ALLOWED";
			case 406 -> "NOT_ACCEPTABLE";
			case 413 -> "ATTACHMENT_TOO_LARGE";
			case 415 -> "UNSUPPORTED_MEDIA_TYPE";
			default -> "INVALID_REQUEST";
		};
	}

	private static String frameworkErrorMessage(org.springframework.web.ErrorResponse frameworkError) {
		String detail = frameworkError.getBody().getDetail();
		if (detail != null && !detail.isBlank()) {
			return detail;
		}
		HttpStatus status = HttpStatus.resolve(frameworkError.getStatusCode().value());
		return status == null ? "request failed" : status.getReasonPhrase();
	}

	private org.springframework.http.ResponseEntity<ErrorResponse> buildResponse(
		HttpStatusCode status,
		String code,
		String message,
		HttpServletRequest request
	) {
		return buildResponse(status, code, message, new HttpHeaders(), request);
	}

	private org.springframework.http.ResponseEntity<ErrorResponse> buildResponse(
		HttpStatusCode status,
		String code,
		String message,
		HttpHeaders headers,
		HttpServletRequest request
	) {
		ErrorResponse body = new ErrorResponse(code, message, Instant.now(), request.getRequestURI());
		return org.springframework.http.ResponseEntity.status(status).headers(headers).body(body);
	}
}
