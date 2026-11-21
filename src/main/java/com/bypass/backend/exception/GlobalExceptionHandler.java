package com.bypass.backend.exception;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidation(MethodArgumentNotValidException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, "Los datos enviados no cumplen el formato requerido.");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> handleMalformedJson(HttpMessageNotReadableException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, "Cuerpo de petición inválido o con campos no permitidos.");
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<?> handleBusinessRule(RuntimeException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<?> handleAccessDenied(AccessDeniedException ex) {
        return buildResponse(HttpStatus.FORBIDDEN, "No tienes permisos para realizar esta acción.");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    public ResponseEntity<?> handleValidation(Exception ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, "Los datos enviados no cumplen el formato requerido.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleUnexpected(Exception ex) {
        log.error("Error interno no controlado", ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Ha ocurrido un error interno en el servidor.");
    }

    private ResponseEntity<?> buildResponse(HttpStatus status, String mensaje) {
        return ResponseEntity.status(status).body(Map.of(
                "timestamp", OffsetDateTime.now().toString(),
                "status", status.value(),
                "error", mensaje
        ));
    }
}



