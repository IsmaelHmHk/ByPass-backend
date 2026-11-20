package com.bypass.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "usuario")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_usuario")
    private Long idUsuario;

    @Column(nullable = false, length = 80)
    private String nombre;

    @Column(nullable = false, unique = true, length = 120)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String rol = "VIAJERO";

    @Column(name = "trust_score", nullable = false, precision = 4, scale = 2)
    @Builder.Default
    private BigDecimal trustScore = new BigDecimal("1.00");

    @Column(name = "total_reportes", nullable = false)
    @Builder.Default
    private Integer totalReportes = 0;

    @Column(name = "reportes_validados", nullable = false)
    @Builder.Default
    private Integer reportesValidados = 0;

    @Column(nullable = false)
    @Builder.Default
    private Boolean activo = true;

    @Column(name = "fecha_registro", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime fechaRegistro = OffsetDateTime.now();
}