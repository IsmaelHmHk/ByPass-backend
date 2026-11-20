package com.bypass.backend.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record ReporteResponseDTO(
        Long idReporte,
        String autor,
        BigDecimal trustScoreAutor,
        String idLinea,
        String idEstacion,
        String idTren,
        String tipoIncidencia,
        String descripcion,
        BigDecimal pesoConfianza,
        String estadoVerificacion,
        OffsetDateTime creadoEn,
        OffsetDateTime expiraEn
) {}