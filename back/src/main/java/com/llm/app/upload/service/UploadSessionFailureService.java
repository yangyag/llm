package com.llm.app.upload.service;

import com.llm.app.upload.repository.UploadSessionRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UploadSessionFailureService {
	private final UploadSessionRepository uploadSessionRepository;

	/**
	 * 업로드 세션 상태를 갱신할 저장소를 주입한다.
	 *
	 * @param uploadSessionRepository 업로드 세션 저장소
	 */
	public UploadSessionFailureService(UploadSessionRepository uploadSessionRepository) {
		this.uploadSessionRepository = uploadSessionRepository;
	}

	/**
	 * 별도 트랜잭션에서 업로드 세션을 실패 상태로 표시한다.
	 *
	 * <p>세션이 이미 삭제된 경우에는 아무 작업도 하지 않으며 원래 예외를 가리지 않는다.</p>
	 *
	 * @param sessionId 실패 처리할 업로드 세션 ID
	 * @param now 실패 시각
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markFailed(UUID sessionId, Instant now) {
		// Tolerate a concurrently-removed session: marking failure must never throw and mask the real error.
		uploadSessionRepository.findById(sessionId)
			.ifPresent(session -> session.markFailed(now));
	}
}
