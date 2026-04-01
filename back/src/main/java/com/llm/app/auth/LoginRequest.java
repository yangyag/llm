package com.llm.app.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record LoginRequest(
    @NotBlank
    @Pattern(regexp = "^[A-Za-z0-9]+$", message = "username must contain English letters and digits only")
    String username,
    @NotBlank String password
) {}
