package com.llm.app.board.service;

import com.llm.app.board.exception.UploadSessionNotFoundException;
import com.llm.app.board.model.UploadSession;
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
		UploadSession session = uploadSessionRepository.findById(sessionId)
			.orElseThrow(() -> new UploadSessionNotFoundException(sessionId));
		session.markFailed(now);
	}
}
