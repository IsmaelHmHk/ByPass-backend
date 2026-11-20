package com.bypass.backend.dto;

import java.math.BigDecimal;

public record AuthResponse(
        String accessToken,
        long expiresInSeconds,
        Long idUsuario,
        String nombre,
        String rol,
        BigDecimal trustScore
) {}