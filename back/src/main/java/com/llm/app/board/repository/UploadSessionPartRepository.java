package com.llm.app.board.repository;

import com.llm.app.board.model.UploadSession;
import com.llm.app.board.model.UploadSessionPart;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UploadSessionPartRepository extends JpaRepository<UploadSessionPart, Long> {
	List<UploadSessionPart> findBySession_IdOrderByChunkNumberAsc(UUID sessionId);

	Optional<UploadSessionPart> findBySession_IdAndChunkNumber(UUID sessionId, int chunkNumber);

	long countBySession_Id(UUID sessionId);

	void deleteBySession(UploadSession session);
}
