package com.llm.app.board.controller;

import com.llm.app.auth.JwtProvider;
import com.llm.app.board.dto.BoardPostDetailResponse;
import com.llm.app.board.dto.EncryptedUploadSessionChunkUploadRequest;
import com.llm.app.board.dto.EncryptedUploadSessionCreateRequest;
import com.llm.app.board.dto.UploadSessionStatusResponse;
import com.llm.app.board.service.UploadSessionService;
import com.llm.app.board.service.UploadSessionWireCodec;
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
	private final JwtProvider jwtProvider;

	public UploadSessionController(
		UploadSessionService uploadSessionService,
		UploadSessionWireCodec uploadSessionWireCodec,
		JwtProvider jwtProvider
	) {
		this.uploadSessionService = uploadSessionService;
		this.uploadSessionWireCodec = uploadSessionWireCodec;
		this.jwtProvider = jwtProvider;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public UploadSessionStatusResponse createSession(
		@RequestHeader(value = "Authorization", required = false) String authHeader,
		@Valid @RequestBody EncryptedUploadSessionCreateRequest request
	) {
		return uploadSessionWireCodec.encodeStatus(
			uploadSessionService.createSession(
				jwtProvider.authenticate(authHeader),
				uploadSessionWireCodec.decodeCreateRequest(request)
			)
		);
	}

	@GetMapping("/{sessionId}")
	public UploadSessionStatusResponse getSession(
		@RequestHeader(value = "Authorization", required = false) String authHeader,
		@PathVariable UUID sessionId
	) {
		return uploadSessionWireCodec.encodeStatus(uploadSessionService.getSession(jwtProvider.authenticate(authHeader), sessionId));
	}

	@PostMapping(value = "/{sessionId}/chunks", consumes = MediaType.APPLICATION_JSON_VALUE)
	public UploadSessionStatusResponse uploadChunk(
		@RequestHeader(value = "Authorization", required = false) String authHeader,
		@PathVariable UUID sessionId,
		@Valid @RequestBody EncryptedUploadSessionChunkUploadRequest request
	) {
		Long userId = jwtProvider.authenticate(authHeader);
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
	public BoardPostDetailResponse finalizeSession(
		@RequestHeader(value = "Authorization", required = false) String authHeader,
		@PathVariable UUID sessionId
	) {
		return uploadSessionService.finalizeSession(jwtProvider.authenticate(authHeader), sessionId);
	}
}
