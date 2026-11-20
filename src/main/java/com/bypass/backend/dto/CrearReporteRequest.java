package com.bypass.backend.dto;

import jakarta.validation.constraints.*;

public record CrearReporteRequest(
        @NotNull Long idUsuario,
        String idLinea,
        String idEstacion,
        String idTren,
        @NotBlank @Pattern(regexp = "RETRASO|AVERIA_TREN|INFRAESTRUCTURA|AGLOMERACION|CORTE_VIA|OTRO")
        String tipoIncidencia,
        @Size(max = 500) String descripcion
) {}