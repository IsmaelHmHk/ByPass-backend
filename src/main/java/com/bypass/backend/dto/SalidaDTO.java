package com.bypass.backend.dto;

public record SalidaDTO(
        String linea,
        String destino,
        int minutos,
        String horaSalida,
        String fuente,
        String estado
) {}