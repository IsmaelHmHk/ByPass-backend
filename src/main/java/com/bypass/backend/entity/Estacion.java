package com.bypass.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "estacion")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Estacion {

    @Id
    @Column(name = "id_estacion", length = 32)
    private String idEstacion;

    @Column(nullable = false, length = 120)
    private String nombre;

    @Column(nullable = false, precision = 10, scale = 7)
    private BigDecimal latitud;

    @Column(nullable = false, precision = 10, scale = 7)
    private BigDecimal longitud;

    @Column(nullable = false, length = 50)
    @Builder.Default
    private String nucleo = "MADRID";

    @Column(name = "tiene_enlace_metro", nullable = false)
    @Builder.Default
    private Boolean tieneEnlaceMetro = false;

    @Column(name = "tiene_enlace_bus", nullable = false)
    @Builder.Default
    private Boolean tieneEnlaceBus = false;
}