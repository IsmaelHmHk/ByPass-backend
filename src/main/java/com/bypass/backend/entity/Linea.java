package com.bypass.backend.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "linea")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Linea {

    @Id
    @Column(name = "id_linea", length = 32)
    private String idLinea;

    @Column(name = "codigo_corto", nullable = false, length = 20)
    private String codigoCorto;

    @Column(name = "nombre_largo", nullable = false, length = 150)
    private String nombreLargo;

    @Column(nullable = false, length = 50)
    @Builder.Default
    private String operador = "RENFE_CERCANIAS";

    @Column(name = "color_hex", nullable = false, length = 7)
    @Builder.Default
    private String colorHex = "#E60000";
}