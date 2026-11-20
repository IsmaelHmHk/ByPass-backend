package com.bypass.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "tren")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Tren {

    @Id
    @Column(name = "id_tren", length = 50)
    private String idTren;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_linea", nullable = false)
    private Linea linea;

    @Column(nullable = false, length = 120)
    private String destino;

    @Column(name = "num_vagones", nullable = false)
    @Builder.Default
    private Integer numVagones = 6;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String estado = "EN_HORA";

    @Column(name = "minutos_retraso", nullable = false)
    @Builder.Default
    private Integer minutosRetraso = 0;

    @Column(name = "ultima_actualizacion", nullable = false)
    @Builder.Default
    private OffsetDateTime ultimaActualizacion = OffsetDateTime.now();
}