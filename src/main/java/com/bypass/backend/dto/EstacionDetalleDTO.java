package com.bypass.backend.dto;

import java.util.List;

public record EstacionDetalleDTO(
        String idEstacion,
        String nombre,
        String operador,
        boolean accesiblePmr,
        String estadoGeneral,
        List<LineaEstadoDTO> lineas,
        List<SalidaDTO> proximasSalidas,
        List<ConexionIntercambiadorDTO> conexionesIntercambiador
) {
    public record LineaEstadoDTO(
            String idLinea,
            String codigoCorto,
            String nombreLargo,
            String estado,
            String avisoIncidencia
    ) {}

    public record ConexionIntercambiadorDTO(
            String idEstacionDestino,
            String nombreDestino,
            int minutosCaminando,
            boolean accesiblePmr,
            String descripcionPaso
    ) {}
}