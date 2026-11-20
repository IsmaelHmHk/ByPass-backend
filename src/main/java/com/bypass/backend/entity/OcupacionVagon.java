package com.bypass.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "ocupacion_vagon", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"id_tren", "numero_vagon"})
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class OcupacionVagon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_ocupacion")
    private Long idOcupacion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_tren", nullable = false)
    private Tren tren;

    @Column(name = "numero_vagon", nullable = false)
    private Integer numeroVagon;

    @Column(name = "nivel_saturacion", nullable = false, length = 20)
    private String nivelSaturacion;

    @Column(name = "score_ponderado", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal scorePonderado = BigDecimal.ZERO;

    @Column(name = "actualizado_en", nullable = false)
    @Builder.Default
    private OffsetDateTime actualizadoEn = OffsetDateTime.now();
}