package com.bypass.backend.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "linea_estacion")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class LineaEstacion {

    @EmbeddedId
    private LineaEstacionId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("idLinea")
    @JoinColumn(name = "id_linea")
    private Linea linea;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("idEstacion")
    @JoinColumn(name = "id_estacion")
    private Estacion estacion;

    @Column(name = "orden_parada", nullable = false)
    private Integer ordenParada;

    @Column(name = "tiempo_siguiente_min")
    private Integer tiempoSiguienteMin;
}