package com.llm.app.upload.controller;

import com.llm.app.auth.api.AuthenticationGateway;
import com.llm.app.upload.dto.EncryptedUploadSessionChunkUploadRequest;
import com.llm.app.upload.dto.EncryptedUploadSessionCreateRequest;
import com.llm.app.upload.dto.UploadSessionStatusResponse;
import com.llm.app.upload.dto.UploadedPostResponse;
import com.llm.app.upload.service.UploadSessionService;
import com.llm.app.upload.service.UploadSessionWireCodec;
import com.llm.app.upload.service.UploadedPostResultMapper;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/upload-sessions")
public class UploadSessionController {
	private final UploadSessionService uploadSessionService;
	private final UploadSessionWireCodec uploadSessionWireCodec;
	private final UploadedPostResultMapper uploadedPostResultMapper;
	private final AuthenticationGateway authenticationGateway;

	public UploadSessionController(
		UploadSessionService uploadSessionService,
		UploadSessionWireCodec uploadSessionWireCodec,
		UploadedPostResultMapper uploadedPostResultMapper,
		AuthenticationGateway authenticationGateway
	) {
		this.uploadSessionService = uploadSessionService;
		this.uploadSessionWireCodec = uploadSessionWireCodec;
		this.uploadedPostResultMapper = uploadedPostResultMapper;
		this.authenticationGateway = authenticationGateway;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public UploadSessionStatusResponse createSession(
		@RequestHeader(value = "Authorization", required = false) String authHeader,
		@Valid @RequestBody EncryptedUploadSessionCreateRequest request
	) {
		return uploadSessionWireCodec.encodeStatus(
			uploadSessionService.createSession(
				authenticationGateway.authenticate(authHeader),
				uploadSessionWireCodec.decodeCreateRequest(request)
			)
		);
	}

	@GetMapping("/{sessionId}")
	public UploadSessionStatusResponse getSession(
		@RequestHeader(value = "Authorization", required = false) String authHeader,
		@PathVariable UUID sessionId
	) {
		return uploadSessionWireCodec.encodeStatus(uploadSessionService.getSession(authenticationGateway.authenticate(authHeader), sessionId));
	}

	@PostMapping(value = "/{sessionId}/chunks", consumes = MediaType.APPLICATION_JSON_VALUE)
	public UploadSessionStatusResponse uploadChunk(
		@RequestHeader(value = "Authorization", required = false) String authHeader,
		@PathVariable UUID sessionId,
		@Valid @RequestBody EncryptedUploadSessionChunkUploadRequest request
	) {
		Long userId = authenticationGateway.authenticate(authHeader);
		var chunkRequest = uploadSessionWireCodec.decodeChunkRequest(request);
		return uploadSessionWireCodec.encodeStatus(
			uploadSessionService.uploadChunk(
				userId,
				sessionId,
				chunkRequest.chunkNumber(),
				chunkRequest.chunkDataBase64()
			)
		);
	}

	@PostMapping("/{sessionId}/finalize")
	public UploadedPostResponse finalizeSession(
		@RequestHeader(value = "Authorization", required = false) String authHeader,
		@PathVariable UUID sessionId
	) {
		return uploadedPostResultMapper.toResponse(
			uploadSessionService.finalizeSession(authenticationGateway.authenticate(authHeader), sessionId)
		);
	}
}
