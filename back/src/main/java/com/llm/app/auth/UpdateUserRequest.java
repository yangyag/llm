package com.llm.app.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** password는 생략 가능(생략 시 기존 비밀번호 유지). role은 필수. */
public record UpdateUserRequest(
    @Size(min = 4, max = 64, message = "password must be 4-64 characters")
    String password,
    @NotBlank
    @Pattern(regexp = "(?i)^(ADMIN|USER)$", message = "role must be ADMIN or USER")
    String role
) {}
