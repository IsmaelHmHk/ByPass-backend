package com.bypass.backend.repository;

import com.bypass.backend.entity.ReporteIncidencia;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.OffsetDateTime;
import java.util.List;

public interface ReporteIncidenciaRepository extends JpaRepository<ReporteIncidencia, Long> {
    List<ReporteIncidencia> findByLineaIdLineaAndExpiraEnAfterAndEstadoVerificacionInOrderByCreadoEnDesc(
            String idLinea,
            OffsetDateTime ahora,
            List<String> estados
    );
}