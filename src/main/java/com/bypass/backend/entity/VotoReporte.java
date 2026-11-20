package com.bypass.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "voto_reporte", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"id_reporte", "id_usuario"})
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class VotoReporte {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_voto")
    private Long idVoto;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_reporte", nullable = false)
    private ReporteIncidencia reporte;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_usuario", nullable = false)
    private Usuario usuario;

    @Column(name = "es_veridico", nullable = false)
    private Boolean esVeridico;

    @Column(name = "votado_en", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime votadoEn = OffsetDateTime.now();
}