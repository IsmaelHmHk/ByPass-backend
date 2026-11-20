package com.bypass.backend.repository;

import com.bypass.backend.entity.OcupacionVagon;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface OcupacionVagonRepository extends JpaRepository<OcupacionVagon, Long> {
    List<OcupacionVagon> findByTrenIdTrenOrderByNumeroVagonAsc(String idTren);
    Optional<OcupacionVagon> findByTrenIdTrenAndNumeroVagon(String idTren, Integer numeroVagon);
}