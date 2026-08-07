package com.llm.app.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
    @NotBlank
    @Pattern(regexp = "^[A-Za-z0-9]+$", message = "username must contain English letters and digits only")
    @Size(max = 100)
    String username,
    @NotBlank
    @Size(min = 4, max = 64, message = "password must be 4-64 characters")
    String password,
    @NotBlank
    @Pattern(regexp = "(?i)^(ADMIN|USER)$", message = "role must be ADMIN or USER")
    String role
) {}
