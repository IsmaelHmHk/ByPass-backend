package com.bypass.backend.dto;

import java.time.OffsetDateTime;

public record TrenActivoDTO(
        String idTren,
        String idLinea,
        String destino,
        Integer numVagones,
        String estado,
        Integer minutosRetraso,
        OffsetDateTime ultimaActualizacion
) {}