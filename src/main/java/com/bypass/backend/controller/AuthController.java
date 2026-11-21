package com.bypass.backend.controller;

import com.bypass.backend.dto.AuthResponse;
import com.bypass.backend.dto.LoginRequest;
import com.bypass.backend.dto.RegistroRequest;
import com.bypass.backend.service.AuthService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Arrays;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Value("${app.cookie.secure:false}")
    private boolean cookieSecure;

    private static final String REFRESH_COOKIE_NAME = "bypass_refresh_token";

    @PostMapping("/anonimo")
    public ResponseEntity<AuthResponse> sesionAnonima() {
        var res = authService.crearSesionAnonima();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, crearCookieRefresh(res.rawRefreshToken(), Duration.ofDays(30)).toString())
                .body(res.authResponse());
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> registrar(@Valid @RequestBody RegistroRequest req) {
        var res = authService.registrar(req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.SET_COOKIE, crearCookieRefresh(res.rawRefreshToken(), Duration.ofDays(7)).toString())
                .body(res.authResponse());
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest req, HttpServletRequest httpRequest) {
        var res = authService.login(req, httpRequest.getRemoteAddr());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, crearCookieRefresh(res.rawRefreshToken(), Duration.ofDays(7)).toString())
                .body(res.authResponse());
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(HttpServletRequest request) {
        String rawRefresh = extraerRefreshCookie(request);
        var res = authService.rotarRefreshToken(rawRefresh);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, crearCookieRefresh(res.rawRefreshToken(), Duration.ofDays(30)).toString())
                .body(res.authResponse());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        String rawRefresh = extraerRefreshCookie(request);
        authService.logout(rawRefresh);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, crearCookieRefresh("", Duration.ZERO).toString())
                .build();
    }

    private ResponseCookie crearCookieRefresh(String valor, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE_NAME, valor)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(maxAge)
                .build();
    }

    private String extraerRefreshCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        return Arrays.stream(request.getCookies())
                .filter(c -> REFRESH_COOKIE_NAME.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }
}