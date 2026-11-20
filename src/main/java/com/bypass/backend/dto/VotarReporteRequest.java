package com.bypass.backend.dto;

import jakarta.validation.constraints.NotNull;

public record VotarReporteRequest(
        @NotNull Long idUsuario,
        @NotNull Boolean esVeridico
) {}