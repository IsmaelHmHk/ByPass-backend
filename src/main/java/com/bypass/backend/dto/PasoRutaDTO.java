package com.bypass.backend.dto;

public record PasoRutaDTO(
        String idLinea,
        String codigoLinea,
        String estacionOrigen,
        String estacionDestino,
        Integer tiempoEstimadoMin,
        Boolean lineaConIncidencias
) {}