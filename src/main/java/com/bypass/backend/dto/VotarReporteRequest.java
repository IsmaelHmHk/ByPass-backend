package com.bypass.backend.dto;

import jakarta.validation.constraints.NotNull;

public record VotarReporteRequest(
        @NotNull
        @NotNull Boolean esVeridico
) {}