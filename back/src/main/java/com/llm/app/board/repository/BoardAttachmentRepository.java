package com.llm.app.board.repository;

import com.llm.app.board.model.BoardAttachment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BoardAttachmentRepository extends JpaRepository<BoardAttachment, Long> {
	List<BoardAttachment> findByPost_IdOrderByCreatedAtAscIdAsc(Long postId);

	Optional<BoardAttachment> findByIdAndPost_Id(Long id, Long postId);
}
