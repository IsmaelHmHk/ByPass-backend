package com.bypass.backend.dto;

public record AlternativaRutaDTO(
        Long idAlternativa,
        String origen,
        String destino,
        String modoTransporte,
        String lineaAlternativa,
        Integer tiempoEstimadoMin,
        String instrucciones
) {}