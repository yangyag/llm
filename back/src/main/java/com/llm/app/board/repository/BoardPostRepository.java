package com.llm.app.board.repository;

import com.llm.app.board.model.BoardPost;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

public interface BoardPostRepository extends JpaRepository<BoardPost, Long> {
	@Query(
		value = """
			select
				p.id as id,
				p.title as title,
				count(r.id) as replyCount,
				p.createdAt as createdAt
			from BoardPost p
			left join p.replies r
			group by p.id, p.title, p.createdAt
			order by p.createdAt desc
			""",
		countQuery = "select count(p) from BoardPost p"
	)
	Page<BoardPostSummaryProjection> findPostSummaries(Pageable pageable);

	@EntityGraph(attributePaths = "replies")
	Optional<BoardPost> findWithRepliesById(Long id);
}
