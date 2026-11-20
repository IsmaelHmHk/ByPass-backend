package com.bypass.backend.repository;

import com.bypass.backend.entity.Estacion;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface EstacionRepository extends JpaRepository<Estacion, String> {
    List<Estacion> findByNucleoOrderByNombreAsc(String nucleo);
}