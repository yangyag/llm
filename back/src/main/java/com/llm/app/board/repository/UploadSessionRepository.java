package com.llm.app.board.repository;

import com.llm.app.board.model.UploadSession;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UploadSessionRepository extends JpaRepository<UploadSession, UUID> {
	List<UploadSession> findByExpiresAtBefore(Instant threshold);
}
