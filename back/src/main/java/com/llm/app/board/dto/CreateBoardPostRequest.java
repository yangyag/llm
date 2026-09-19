package com.llm.app.board.dto;

import com.llm.app.board.model.BoardPostMode;
import com.llm.app.board.model.PostBodyFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

public class CreateBoardPostRequest {
	@NotBlank(message = "title is required")
	@Size(max = 200, message = "title must be 200 characters or less")
	private String title;

	private String bodyBase64;

	private PostBodyFormat bodyFormat = PostBodyFormat.PLAIN_TEXT;

	private String bodyDocumentBase64;

	private BoardPostMode mode = BoardPostMode.NORMAL;

	private List<MultipartFile> attachments;

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getBodyBase64() {
		return bodyBase64;
	}

	public void setBodyBase64(String bodyBase64) {
		this.bodyBase64 = bodyBase64;
	}

	public PostBodyFormat getBodyFormat() {
		return bodyFormat;
	}

	public void setBodyFormat(PostBodyFormat bodyFormat) {
		if (bodyFormat != null) {
			this.bodyFormat = bodyFormat;
		}
	}

	public String getBodyDocumentBase64() {
		return bodyDocumentBase64;
	}

	public void setBodyDocumentBase64(String bodyDocumentBase64) {
		this.bodyDocumentBase64 = bodyDocumentBase64;
	}

	public BoardPostMode getMode() {
		return mode;
	}

	public void setMode(BoardPostMode mode) {
		if (mode != null) {
			this.mode = mode;
		}
	}

	public List<MultipartFile> getAttachments() {
		return attachments;
	}

	public void setAttachments(List<MultipartFile> attachments) {
		this.attachments = attachments;
	}
}
