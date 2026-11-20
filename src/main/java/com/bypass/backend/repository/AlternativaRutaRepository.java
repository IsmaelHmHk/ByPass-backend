package com.bypass.backend.repository;

import com.bypass.backend.entity.AlternativaRuta;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AlternativaRutaRepository extends JpaRepository<AlternativaRuta, Long> {
    List<AlternativaRuta> findByEstacionOrigenIdEstacionAndEstacionDestinoIdEstacionOrderByTiempoEstimadoMinAsc(
            String idOrigen,
            String idDestino
    );
}