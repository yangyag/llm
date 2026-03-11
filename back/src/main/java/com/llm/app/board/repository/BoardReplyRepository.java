package com.llm.app.board.repository;

import com.llm.app.board.model.BoardReply;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BoardReplyRepository extends JpaRepository<BoardReply, Long> {
}
