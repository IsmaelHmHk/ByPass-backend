package com.bypass.backend.dto;

import java.util.List;

public record RutaCalculadaDTO(
        String tipoRuta,
        Integer tiempoTotalMin,
        Integer transbordos,
        Boolean evitaIncidencias,
        List<PasoRutaDTO> pasos,
        List<AlternativaRutaDTO> alternativasIntermodales
) {}