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

	// ponytail: derived delete (loads + removes managed rows) — required, not a bulk @Modifying delete:
	// finalizeSession holds the chunk entities as managed, so a bulk delete leaves them dangling and
	// Hibernate throws TransientObjectException at flush. Keep this unless finalize stops pre-loading parts.
	void deleteBySession(UploadSession session);
}
