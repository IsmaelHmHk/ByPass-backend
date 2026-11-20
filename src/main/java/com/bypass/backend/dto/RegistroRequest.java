package com.bypass.backend.dto;

import jakarta.validation.constraints.*;

public record RegistroRequest(
        @NotBlank @Size(min = 2, max = 80) String nombre,
        @NotBlank @Email @Size(max = 120) String email,
        @NotBlank @Size(min = 10, max = 72)
        @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$",
                message = "La contraseña debe tener al menos 10 caracteres, mayúscula, minúscula y número.")
        String password
) {}