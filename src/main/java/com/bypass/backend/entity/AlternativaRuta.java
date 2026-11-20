package com.bypass.backend.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "alternativa_ruta")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class AlternativaRuta {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_alternativa")
    private Long idAlternativa;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_estacion_origen", nullable = false)
    private Estacion estacionOrigen;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_estacion_destino", nullable = false)
    private Estacion estacionDestino;

    @Column(name = "modo_transporte", nullable = false, length = 20)
    private String modoTransporte;

    @Column(name = "linea_alternativa", nullable = false, length = 50)
    private String lineaAlternativa;

    @Column(name = "tiempo_estimado_min", nullable = false)
    private Integer tiempoEstimadoMin;

    @Column(nullable = false, length = 300)
    private String instrucciones;
}