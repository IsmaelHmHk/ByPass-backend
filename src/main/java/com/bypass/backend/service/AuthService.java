package com.bypass.backend.service;

import com.bypass.backend.dto.*;
import com.bypass.backend.entity.RefreshToken;
import com.bypass.backend.entity.Usuario;
import com.bypass.backend.repository.RefreshTokenRepository;
import com.bypass.backend.repository.UsuarioRepository;
import com.bypass.backend.security.JwtService;
import com.bypass.backend.security.LoginRateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UsuarioRepository usuarioRepo;
    private final RefreshTokenRepository refreshTokenRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginRateLimiter rateLimiter;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Value("${app.jwt.refresh-expiration-days:7}")
    private long refreshExpirationDays;

    public record ResultadoSesion(AuthResponse authResponse, String rawRefreshToken) {}

    @Transactional
    public ResultadoSesion registrar(RegistroRequest req) {
        String emailNorm = req.email().trim().toLowerCase();
        if (usuarioRepo.findByEmail(emailNorm).isPresent()) {
            throw new IllegalArgumentException("No se pudo completar el registro con los datos proporcionados.");
        }

        Usuario nuevo = Usuario.builder()
                .nombre(req.nombre().trim())
                .email(emailNorm)
                .passwordHash(passwordEncoder.encode(req.password()))
                .rol("VIAJERO")
                .build();

        Usuario guardado = usuarioRepo.save(nuevo);
        return emitirParTokens(guardado, UUID.randomUUID().toString());
    }

    @Transactional
    public ResultadoSesion login(LoginRequest req, String ipCliente) {
        String emailNorm = req.email().trim().toLowerCase();
        String claveLimite = emailNorm + ":" + ipCliente;
        rateLimiter.verificarPermitido(claveLimite);

        Usuario usuario = usuarioRepo.findByEmail(emailNorm).orElse(null);
        if (usuario == null || !usuario.getActivo()
                || !passwordEncoder.matches(req.password(), usuario.getPasswordHash())) {
            rateLimiter.registrarFallo(claveLimite);
            throw new IllegalArgumentException("Credenciales inválidas.");
        }

        rateLimiter.limpiar(claveLimite);
        return emitirParTokens(usuario, UUID.randomUUID().toString());
    }

    @Transactional
    public ResultadoSesion rotarRefreshToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("Sesión no válida.");
        }

        String hash = sha256Hex(rawToken);
        RefreshToken tokenActual = refreshTokenRepo.findByTokenHash(hash)
                .orElseThrow(() -> new IllegalArgumentException("Sesión no válida."));

        // Detección de robo/reutilización: si ya estaba revocado, invalidamos toda la familia
        if (tokenActual.getRevocado()) {
            refreshTokenRepo.revocarFamilia(tokenActual.getFamiliaToken());
            throw new IllegalStateException("Actividad anómala detectada. Inicia sesión de nuevo.");
        }

        if (tokenActual.getExpiraEn().isBefore(OffsetDateTime.now())) {
            tokenActual.setRevocado(true);
            refreshTokenRepo.save(tokenActual);
            throw new IllegalArgumentException("La sesión ha caducado.");
        }

        tokenActual.setRevocado(true);
        refreshTokenRepo.save(tokenActual);

        return emitirParTokens(tokenActual.getUsuario(), tokenActual.getFamiliaToken());
    }

    @Transactional
    public void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        refreshTokenRepo.findByTokenHash(sha256Hex(rawToken)).ifPresent(t -> {
            t.setRevocado(true);
            refreshTokenRepo.save(t);
        });
    }
    @Transactional
    public ResultadoSesion crearSesionAnonima() {
        String sufijo = UUID.randomUUID().toString().substring(0, 8);
        String emailTecnico = "anon_" + sufijo + "@bypass.local";

        Usuario anonimo = Usuario.builder()
                .nombre("Viajero_" + sufijo.toUpperCase())
                .email(emailTecnico)
                .passwordHash(passwordEncoder.encode(UUID.randomUUID().toString()))
                .rol("VIAJERO")
                .trustScore(new java.math.BigDecimal("0.50")) // Peso reducido hasta ganar reputación real
                .build();

        Usuario guardado = usuarioRepo.save(anonimo);
        return emitirParTokens(guardado, UUID.randomUUID().toString());
    }

    private ResultadoSesion emitirParTokens(Usuario usuario, String familia) {
        String accessToken = jwtService.generarAccessToken(usuario);
        String rawRefresh = generarTokenOpaco();

        RefreshToken entity = RefreshToken.builder()
                .usuario(usuario)
                .tokenHash(sha256Hex(rawRefresh))
                .familiaToken(familia)
                .expiraEn(OffsetDateTime.now().plusDays(refreshExpirationDays))
                .build();

        refreshTokenRepo.save(entity);

        AuthResponse response = new AuthResponse(
                accessToken,
                900L,
                usuario.getIdUsuario(),
                usuario.getNombre(),
                usuario.getRol(),
                usuario.getTrustScore()
        );
        return new ResultadoSesion(response, rawRefresh);
    }

    private String generarTokenOpaco() {
        byte[] bytes = new byte[48];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}