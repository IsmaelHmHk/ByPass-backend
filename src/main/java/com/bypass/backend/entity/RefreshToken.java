package com.bypass.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "refresh_token")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_token")
    private Long idToken;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_usuario", nullable = false)
    private Usuario usuario;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "familia_token", nullable = false, length = 36)
    private String familiaToken;

    @Column(name = "expira_en", nullable = false)
    private OffsetDateTime expiraEn;

    @Column(nullable = false)
    @Builder.Default
    private Boolean revocado = false;

    @Column(name = "creado_en", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime creadoEn = OffsetDateTime.now();
}