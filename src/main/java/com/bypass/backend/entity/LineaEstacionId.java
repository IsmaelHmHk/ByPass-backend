package com.bypass.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;
import java.io.Serializable;

@Embeddable
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode
public class LineaEstacionId implements Serializable {

    @Column(name = "id_linea", length = 32)
    private String idLinea;

    @Column(name = "id_estacion", length = 32)
    private String idEstacion;
}