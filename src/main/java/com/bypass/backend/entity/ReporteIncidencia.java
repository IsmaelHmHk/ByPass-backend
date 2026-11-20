package com.bypass.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "reporte_incidencia")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class ReporteIncidencia {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_reporte")
    private Long idReporte;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_usuario", nullable = false)
    private Usuario usuario;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_linea")
    private Linea linea;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_estacion")
    private Estacion estacion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_tren")
    private Tren tren;

    @Column(name = "tipo_incidencia", nullable = false, length = 30)
    private String tipoIncidencia;

    @Column(length = 500)
    private String descripcion;

    @Column(name = "peso_confianza", nullable = false, precision = 4, scale = 2)
    @Builder.Default
    private BigDecimal pesoConfianza = new BigDecimal("1.00");

    @Column(name = "estado_verificacion", nullable = false, length = 20)
    @Builder.Default
    private String estadoVerificacion = "PENDIENTE";

    @Column(name = "creado_en", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime creadoEn = OffsetDateTime.now();

    @Column(name = "expira_en", nullable = false)
    private OffsetDateTime expiraEn;
}