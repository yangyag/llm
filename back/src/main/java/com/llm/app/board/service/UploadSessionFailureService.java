package com.llm.app.board.service;

import com.llm.app.board.repository.UploadSessionRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UploadSessionFailureService {
	private final UploadSessionRepository uploadSessionRepository;

	public UploadSessionFailureService(UploadSessionRepository uploadSessionRepository) {
		this.uploadSessionRepository = uploadSessionRepository;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markFailed(UUID sessionId, Instant now) {
		// Tolerate a concurrently-removed session: marking failure must never throw and mask the real error.
		uploadSessionRepository.findById(sessionId)
			.ifPresent(session -> session.markFailed(now));
	}
}
