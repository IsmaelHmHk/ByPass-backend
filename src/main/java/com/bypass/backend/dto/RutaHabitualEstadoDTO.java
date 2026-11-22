package com.bypass.backend.dto;

import java.util.List;

public class RutaHabitualEstadoDTO {

    public record ConsultaRequest(List<TrayectoItem> trayectos) {}

    public record TrayectoItem(String id, String origen, String destino) {}

    public record EstadoResponse(
            String id,
            String nombreOrigen,
            String nombreDestino,
            String estado,
            int duracionNormalMin,
            int duracionActualEstimadaMin,
            boolean hayAlternativaMejor,
            String resumenAlerta
    ) {}
}