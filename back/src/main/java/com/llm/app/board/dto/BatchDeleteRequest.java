package com.llm.app.board.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record BatchDeleteRequest(
    @NotEmpty List<Long> ids
) {}
