package com.llm.app.board.dto;

import com.llm.app.board.model.BoardPostMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

public class UpdateBoardPostRequest {
	@NotBlank(message = "title is required")
	@Size(max = 200, message = "title must be 200 characters or less")
	private String title;

	private String bodyBase64;

	private BoardPostMode mode = BoardPostMode.NORMAL;

	private List<MultipartFile> attachments;

	private List<Long> removeAttachmentIds;

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

	public List<Long> getRemoveAttachmentIds() {
		return removeAttachmentIds;
	}

	public void setRemoveAttachmentIds(List<Long> removeAttachmentIds) {
		this.removeAttachmentIds = removeAttachmentIds;
	}
}
