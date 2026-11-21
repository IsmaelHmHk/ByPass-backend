package com.bypass.backend.dto;

import java.math.BigDecimal;

public record EstacionParadaDTO(
        String idEstacion,
        String nombre,
        Integer ordenParada,
        Integer tiempoSiguienteMin,
        BigDecimal latitud,
        BigDecimal longitud,
        Boolean tieneEnlaceMetro,
        Boolean tieneEnlaceBus
) {}