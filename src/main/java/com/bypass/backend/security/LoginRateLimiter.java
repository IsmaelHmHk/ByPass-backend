package com.bypass.backend.security;

import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LoginRateLimiter {

    private static final int MAX_INTENTOS = 5;
    private static final long VENTANA_SEGUNDOS = 900; // 15 minutos

    private record RegistroIntento(int intentos, Instant primerIntento) {}

    private final Map<String, RegistroIntento> intentosPorClave = new ConcurrentHashMap<>();

    public void verificarPermitido(String clave) {
        RegistroIntento reg = intentosPorClave.get(clave);
        if (reg == null) return;

        if (Instant.now().isAfter(reg.primerIntento().plusSeconds(VENTANA_SEGUNDOS))) {
            intentosPorClave.remove(clave);
            return;
        }
        if (reg.intentos() >= MAX_INTENTOS) {
            throw new IllegalStateException("Demasiados intentos fallidos. Inténtalo de nuevo en 15 minutos.");
        }
    }

    public void registrarFallo(String clave) {
        intentosPorClave.compute(clave, (k, actual) -> {
            Instant ahora = Instant.now();
            if (actual == null || ahora.isAfter(actual.primerIntento().plusSeconds(VENTANA_SEGUNDOS))) {
                return new RegistroIntento(1, ahora);
            }
            return new RegistroIntento(actual.intentos() + 1, actual.primerIntento());
        });
    }

    public void limpiar(String clave) {
        intentosPorClave.remove(clave);
    }
}