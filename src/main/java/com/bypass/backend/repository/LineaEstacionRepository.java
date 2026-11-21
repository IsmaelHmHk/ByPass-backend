package com.bypass.backend.repository;

import com.bypass.backend.entity.LineaEstacion;
import com.bypass.backend.entity.LineaEstacionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface LineaEstacionRepository extends JpaRepository<LineaEstacion, LineaEstacionId> {

    @Query("SELECT le FROM LineaEstacion le JOIN FETCH le.estacion WHERE le.linea.idLinea = :idLinea ORDER BY le.ordenParada ASC")
    List<LineaEstacion> findRecorridoPorLinea(String idLinea);

    @Query("SELECT le FROM LineaEstacion le JOIN FETCH le.linea WHERE le.estacion.idEstacion = :idEstacion")
    List<LineaEstacion> findLineasPorEstacion(String idEstacion);
}