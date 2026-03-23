package com.llm.app.board.dto;

import com.llm.app.board.model.BoardPostMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.multipart.MultipartFile;

public class UpdateBoardPostRequest {
	@NotBlank(message = "title is required")
	@Size(max = 200, message = "title must be 200 characters or less")
	private String title;

	@NotBlank(message = "bodyBase64 is required")
	private String bodyBase64;

	private BoardPostMode mode = BoardPostMode.NORMAL;

	@NotBlank(message = "password is required")
	@Size(max = 100, message = "password must be 100 characters or less")
	private String password;

	private MultipartFile attachment;

	private boolean removeAttachment;

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

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public MultipartFile getAttachment() {
		return attachment;
	}

	public void setAttachment(MultipartFile attachment) {
		this.attachment = attachment;
	}

	public boolean isRemoveAttachment() {
		return removeAttachment;
	}

	public void setRemoveAttachment(boolean removeAttachment) {
		this.removeAttachment = removeAttachment;
	}
}
