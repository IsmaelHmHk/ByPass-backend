package com.bypass.backend.repository;

import com.bypass.backend.entity.Estacion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface EstacionRepository extends JpaRepository<Estacion, String> {

    List<Estacion> findByNucleoOrderByNombreAsc(String nucleo);

    @Query("""
        SELECT e FROM Estacion e
        WHERE e.latitud BETWEEN :minLat AND :maxLat
          AND e.longitud BETWEEN :minLon AND :maxLon
        """)
    List<Estacion> findEnBoundingBox(
            @Param("minLat") BigDecimal minLat,
            @Param("maxLat") BigDecimal maxLat,
            @Param("minLon") BigDecimal minLon,
            @Param("maxLon") BigDecimal maxLon
    );
}