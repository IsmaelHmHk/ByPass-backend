package com.bypass.backend.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record VagonEstadoDTO(
        Integer numeroVagon,
        String nivelSaturacion,
        BigDecimal scorePonderado,
        OffsetDateTime actualizadoEn
) {}