package com.llm.app.board.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llm.app.board.ai.AiProvider;
import com.llm.app.board.ai.AiReplyGenerator;
import com.llm.app.board.model.BoardPost;
import com.llm.app.board.model.BoardReply;
import com.llm.app.board.repository.BoardPostRepository;
import com.llm.app.board.repository.BoardReplyRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BoardPostControllerTest {
	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private BoardPostRepository boardPostRepository;

	@Autowired
	private BoardReplyRepository boardReplyRepository;

	@MockBean
	private AiReplyGenerator aiReplyGenerator;

	@BeforeEach
	void setUp() {
		boardReplyRepository.deleteAll();
		boardPostRepository.deleteAll();
	}

	@Test
	void postAndReplyCrudShouldWork() throws Exception {
		MvcResult createResult = mockMvc.perform(post("/api/v1/posts")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "title": "첫 글",
					  "bodyBase64": "%s",
					  "password": "secret"
					}
					""".formatted(encode("첫 번째 게시글 본문"))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id", notNullValue()))
			.andExpect(jsonPath("$.title").value("첫 글"))
			.andExpect(jsonPath("$.body").value("첫 번째 게시글 본문"))
			.andReturn();

		long postId = extractId(createResult.getResponse().getContentAsString());

		mockMvc.perform(get("/api/v1/posts"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.page").value(1))
			.andExpect(jsonPath("$.pageSize").value(10))
			.andExpect(jsonPath("$.totalItems").value(1))
			.andExpect(jsonPath("$.totalPages").value(1))
			.andExpect(jsonPath("$.items[0].replyCount").value(0));

		MvcResult replyResult = mockMvc.perform(post("/api/v1/posts/{id}/replies", postId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "bodyBase64": "%s",
					  "password": "reply-secret"
					}
					""".formatted(encode("첫 답변"))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.replies", hasSize(1)))
			.andExpect(jsonPath("$.replies[0].body").value("첫 답변"))
			.andReturn();

		long replyId = extractFirstReplyId(replyResult.getResponse().getContentAsString());

		mockMvc.perform(put("/api/v1/posts/{id}", postId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "title": "수정된 글",
					  "bodyBase64": "%s",
					  "password": "secret"
					}
					""".formatted(encode("수정된 본문"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("수정된 글"))
			.andExpect(jsonPath("$.body").value("수정된 본문"));

		mockMvc.perform(put("/api/v1/posts/replies/{id}", replyId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "bodyBase64": "%s",
					  "password": "reply-secret"
					}
					""".formatted(encode("수정된 답변"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.replies[0].body").value("수정된 답변"));

		mockMvc.perform(delete("/api/v1/posts/replies/{id}", replyId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "password": "reply-secret"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.replies", hasSize(0)));

		mockMvc.perform(delete("/api/v1/posts/{id}", postId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "password": "secret"
					}
					"""))
			.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/posts/{id}", postId))
			.andExpect(status().isNotFound());
	}

	@Test
	void invalidBase64ShouldReturnBadRequest() throws Exception {
		mockMvc.perform(post("/api/v1/posts")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "title": "bad",
					  "bodyBase64": "%%%bad%%%",
					  "password": "secret"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_ENCODED_BODY"));
	}

	@Test
	void bodyShouldPreserveLeadingTrailingWhitespace() throws Exception {
		String originalBody = "  첫 줄\n둘째 줄  \n";

		MvcResult createResult = mockMvc.perform(post("/api/v1/posts")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "title": "공백 보존",
					  "bodyBase64": "%s",
					  "password": "secret"
					}
					""".formatted(encode(originalBody))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.body").value(originalBody))
			.andReturn();

		long postId = extractId(createResult.getResponse().getContentAsString());
		assertThat(boardPostRepository.findById(postId)).isPresent();
		assertThat(boardPostRepository.findById(postId).orElseThrow().getBody()).isEqualTo(originalBody);
	}

	@Test
	void bodyLongerThan20000ShouldStillBeAccepted() throws Exception {
		String largeBody = "a".repeat(30_000);
		MvcResult createResult = mockMvc.perform(post("/api/v1/posts")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "title": "긴 본문",
					  "bodyBase64": "%s",
					  "password": "secret"
					}
					""".formatted(encode(largeBody))))
			.andExpect(status().isCreated())
			.andReturn();

		long postId = extractId(createResult.getResponse().getContentAsString());
		assertThat(boardPostRepository.findById(postId)).isPresent();
		assertThat(boardPostRepository.findById(postId).orElseThrow().getBody()).hasSize(30_000);

		mockMvc.perform(post("/api/v1/posts/{id}/replies", postId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "bodyBase64": "%s",
					  "password": "reply-secret"
					}
					""".formatted(encode(largeBody))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.replies", hasSize(1)));

		assertThat(boardReplyRepository.findAll()).hasSize(1);
		assertThat(boardReplyRepository.findAll().get(0).getBody()).hasSize(30_000);
	}

	@Test
	void bodyLongerThanOneMillionShouldReturnBadRequest() throws Exception {
		String tooLargeBody = "a".repeat(1_000_001);

		mockMvc.perform(post("/api/v1/posts")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "title": "너무 긴 본문",
					  "bodyBase64": "%s",
					  "password": "secret"
					}
					""".formatted(encode(tooLargeBody))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_ENCODED_BODY"));
	}

	@Test
	void wrongPasswordShouldReturnForbidden() throws Exception {
		MvcResult createResult = mockMvc.perform(post("/api/v1/posts")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "title": "pw",
					  "bodyBase64": "%s",
					  "password": "right"
					}
					""".formatted(encode("비밀번호 테스트"))))
			.andExpect(status().isCreated())
			.andReturn();

		long postId = extractId(createResult.getResponse().getContentAsString());

		MvcResult replyResult = mockMvc.perform(post("/api/v1/posts/{id}/replies", postId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "bodyBase64": "%s",
					  "password": "reply-right"
					}
					""".formatted(encode("답변 비밀번호 테스트"))))
			.andExpect(status().isCreated())
			.andReturn();

		long replyId = extractFirstReplyId(replyResult.getResponse().getContentAsString());

		mockMvc.perform(put("/api/v1/posts/{id}", postId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "title": "수정 시도",
					  "bodyBase64": "%s",
					  "password": "wrong"
					}
					""".formatted(encode("수정 실패 본문"))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("INVALID_PASSWORD"));

		mockMvc.perform(delete("/api/v1/posts/{id}", postId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "password": "wrong"
					}
					"""))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("INVALID_PASSWORD"));

		mockMvc.perform(put("/api/v1/posts/replies/{id}", replyId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "bodyBase64": "%s",
					  "password": "wrong"
					}
					""".formatted(encode("답변 수정 실패"))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("INVALID_PASSWORD"));

		mockMvc.perform(delete("/api/v1/posts/replies/{id}", replyId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "password": "wrong"
					}
					"""))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("INVALID_PASSWORD"));
	}

	@Test
	void postsShouldBePaginatedByTenItems() throws Exception {
		Instant baseTime = Instant.parse("2026-03-11T00:00:00Z");
		BoardPost latestPost = null;
		BoardPost twelfthPost = null;
		for (int i = 1; i <= 21; i++) {
			BoardPost savedPost = boardPostRepository.save(new BoardPost(
				"글 " + i,
				"본문 " + i,
				"hash-" + i,
				baseTime.plusSeconds(i),
				baseTime.plusSeconds(i)
			));
			if (i == 21) {
				latestPost = savedPost;
			}
			if (i == 12) {
				twelfthPost = savedPost;
			}
		}
		boardPostRepository.flush();
		boardReplyRepository.save(new BoardReply(latestPost, "답변 A", "hash-a", baseTime.plusSeconds(30), baseTime.plusSeconds(30)));
		boardReplyRepository.save(new BoardReply(latestPost, "답변 B", "hash-b", baseTime.plusSeconds(31), baseTime.plusSeconds(31)));
		boardReplyRepository.save(new BoardReply(twelfthPost, "답변 C", "hash-c", baseTime.plusSeconds(32), baseTime.plusSeconds(32)));
		boardReplyRepository.flush();

		mockMvc.perform(get("/api/v1/posts").param("page", "1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(10)))
			.andExpect(jsonPath("$.items[0].title").value("글 21"))
			.andExpect(jsonPath("$.items[0].replyCount").value(2))
			.andExpect(jsonPath("$.items[9].title").value("글 12"))
			.andExpect(jsonPath("$.items[9].replyCount").value(1))
			.andExpect(jsonPath("$.page").value(1))
			.andExpect(jsonPath("$.pageSize").value(10))
			.andExpect(jsonPath("$.totalItems").value(21))
			.andExpect(jsonPath("$.totalPages").value(3))
			.andExpect(jsonPath("$.hasPrevious").value(false))
			.andExpect(jsonPath("$.hasNext").value(true));

		mockMvc.perform(get("/api/v1/posts").param("page", "2"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(10)))
			.andExpect(jsonPath("$.items[0].title").value("글 11"))
			.andExpect(jsonPath("$.items[9].title").value("글 2"))
			.andExpect(jsonPath("$.page").value(2))
			.andExpect(jsonPath("$.pageSize").value(10))
			.andExpect(jsonPath("$.totalItems").value(21))
			.andExpect(jsonPath("$.totalPages").value(3))
			.andExpect(jsonPath("$.hasPrevious").value(true))
			.andExpect(jsonPath("$.hasNext").value(true));

		mockMvc.perform(get("/api/v1/posts").param("page", "3"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].title").value("글 1"))
			.andExpect(jsonPath("$.page").value(3))
			.andExpect(jsonPath("$.pageSize").value(10))
			.andExpect(jsonPath("$.totalItems").value(21))
			.andExpect(jsonPath("$.totalPages").value(3))
			.andExpect(jsonPath("$.hasPrevious").value(true))
			.andExpect(jsonPath("$.hasNext").value(false));
	}

	@Test
	void aiReplyShouldBeStoredAndLocked() throws Exception {
		MvcResult createResult = mockMvc.perform(post("/api/v1/posts")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "title": "AI 테스트",
					  "bodyBase64": "%s",
					  "password": "secret"
					}
					""".formatted(encode("AI가 답변할 본문"))))
			.andExpect(status().isCreated())
			.andReturn();

		long postId = extractId(createResult.getResponse().getContentAsString());
		given(aiReplyGenerator.generateReply(eq(AiProvider.GPT), anyString(), anyString())).willReturn("AI 생성 답변");

		MvcResult aiReplyResult = mockMvc.perform(post("/api/v1/posts/{id}/ai-replies", postId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "provider": "GPT"
					}
					"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.replies", hasSize(1)))
			.andExpect(jsonPath("$.replies[0].body").value("AI 생성 답변"))
			.andExpect(jsonPath("$.replies[0].ai").value(true))
			.andExpect(jsonPath("$.replies[0].aiProvider").value("GPT"))
			.andReturn();

		long replyId = extractFirstReplyId(aiReplyResult.getResponse().getContentAsString());

		mockMvc.perform(put("/api/v1/posts/replies/{id}", replyId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "bodyBase64": "%s",
					  "password": "any"
					}
					""".formatted(encode("수정 시도"))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("AI_REPLY_LOCKED"));

		mockMvc.perform(delete("/api/v1/posts/replies/{id}", replyId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "password": "any"
					}
					"""))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("AI_REPLY_LOCKED"));
	}

	@Test
	void invalidAiProviderShouldReturnBadRequest() throws Exception {
		MvcResult createResult = mockMvc.perform(post("/api/v1/posts")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "title": "AI 공급자 테스트",
					  "bodyBase64": "%s",
					  "password": "secret"
					}
					""".formatted(encode("본문"))))
			.andExpect(status().isCreated())
			.andReturn();

		long postId = extractId(createResult.getResponse().getContentAsString());

		mockMvc.perform(post("/api/v1/posts/{id}/ai-replies", postId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "provider": "BARD"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_AI_PROVIDER"));
	}

	private String encode(String value) {
		return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	private long extractId(String responseBody) {
		try {
			return objectMapper.readTree(responseBody).path("id").asLong();
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to extract post id", exception);
		}
	}

	private long extractFirstReplyId(String responseBody) {
		try {
			return objectMapper.readTree(responseBody).path("replies").get(0).path("id").asLong();
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to extract reply id", exception);
		}
	}
}
