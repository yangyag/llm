package com.llm.app.board.repository;

import com.llm.app.board.model.AttachmentFileDeletion;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttachmentFileDeletionRepository extends JpaRepository<AttachmentFileDeletion, String> {
    List<AttachmentFileDeletion> findTop100ByOrderByCreatedAtAsc();
}
