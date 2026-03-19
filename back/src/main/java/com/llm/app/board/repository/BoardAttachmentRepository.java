package com.llm.app.board.repository;

import com.llm.app.board.model.BoardAttachment;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BoardAttachmentRepository extends JpaRepository<BoardAttachment, Long> {
	Optional<BoardAttachment> findByPost_Id(Long postId);
}
