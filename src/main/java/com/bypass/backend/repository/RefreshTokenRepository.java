package com.bypass.backend.repository;

import com.bypass.backend.entity.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("UPDATE RefreshToken r SET r.revocado = true WHERE r.familiaToken = :familia")
    void revocarFamilia(String familia);

    @Modifying
    @Query("UPDATE RefreshToken r SET r.revocado = true WHERE r.usuario.idUsuario = :idUsuario")
    void revocarTodosPorUsuario(Long idUsuario);
}