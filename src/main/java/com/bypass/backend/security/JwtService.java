package com.bypass.backend.security;

import com.bypass.backend.entity.Usuario;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;

@Service
public class JwtService {

    private final SecretKey signingKey;
    private final long accessExpirationMs;

    public JwtService(
            @Value("${app.jwt.secret}") String secretBase64,
            @Value("${app.jwt.access-expiration-ms:900000}") long accessExpirationMs) {
        this.signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secretBase64));
        this.accessExpirationMs = accessExpirationMs;
    }

    public String generarAccessToken(Usuario usuario) {
        Date ahora = new Date();
        Date expiracion = new Date(ahora.getTime() + accessExpirationMs);

        return Jwts.builder()
                .subject(usuario.getEmail())
                .claim("uid", usuario.getIdUsuario())
                .claim("rol", usuario.getRol())
                .issuedAt(ahora)
                .expiration(expiracion)
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    public Claims validarYExtraerClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}