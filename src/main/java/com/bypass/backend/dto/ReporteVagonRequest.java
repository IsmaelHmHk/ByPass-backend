package com.bypass.backend.dto;

import jakarta.validation.constraints.*;

public record ReporteVagonRequest(
        @NotNull @Min(1) @Max(12) Integer numeroVagon,
        @NotBlank @Pattern(regexp = "VACIO|MODERADO|LLENO|COLAPSADO") String nivelSaturacion
) {}