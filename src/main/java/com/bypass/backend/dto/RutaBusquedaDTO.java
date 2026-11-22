package com.bypass.backend.dto;

import java.util.List;

public record RutaBusquedaDTO(
        EstacionRef origen,
        EstacionRef destino,
        boolean hayAlertaBypass,
        String mensajeBypass,
        RutaOpcionDTO rutaRecomendada,
        List<RutaOpcionDTO> alternativas
) {
    public record EstacionRef(String idEstacion, String nombre) {}

    public record RutaOpcionDTO(
            String idRuta,
            String etiqueta,
            int duracionTotalMin,
            String horaLlegadaEstimada,
            int numTransbordos,
            boolean accesiblePmr,
            String fiabilidad,
            Integer diferenciaMin,
            String explicacion,
            List<TramoRutaDTO> tramos
    ) {}

    public record TramoRutaDTO(
            int orden,
            String tipo,
            String operador,
            String linea,
            String origen,
            String destino,
            int duracionMin,
            int esperaMin,
            String fuenteDato,
            String estado,
            boolean accesiblePmr,
            List<String> paradasIntermedias
    ) {}
}