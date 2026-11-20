package com.bypass.backend.repository;

import com.bypass.backend.entity.Tren;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TrenRepository extends JpaRepository<Tren, String> {
    List<Tren> findByLineaIdLinea(String idLinea);
}