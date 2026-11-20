package com.bypass.backend.repository;

import com.bypass.backend.entity.VotoReporte;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VotoReporteRepository extends JpaRepository<VotoReporte, Long> {
    boolean existsByReporteIdReporteAndUsuarioIdUsuario(Long idReporte, Long idUsuario);
}