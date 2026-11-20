package com.bypass.backend.controller;

import com.bypass.backend.dto.*;
import com.bypass.backend.service.IncidenciaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/incidencias")
@RequiredArgsConstructor
public class IncidenciaController {

    private final IncidenciaService incidenciaService;

    @PostMapping
    @PreAuthorize("hasAnyRole('VIAJERO', 'MODERADOR', 'ADMIN')")
    public ResponseEntity<ReporteResponseDTO> crear(
            @Valid @RequestBody CrearReporteRequest request,
            Authentication auth) {
        Long idUsuarioAutenticado = (Long) auth.getPrincipal();
        return ResponseEntity.ok(incidenciaService.crearReporte(idUsuarioAutenticado, request));
    }

    @PostMapping("/{idReporte}/votar")
    @PreAuthorize("hasAnyRole('VIAJERO', 'MODERADOR', 'ADMIN')")
    public ResponseEntity<ReporteResponseDTO> votar(
            @PathVariable Long idReporte,
            @Valid @RequestBody VotarReporteRequest request,
            Authentication auth) {
        Long idUsuarioAutenticado = (Long) auth.getPrincipal();
        return ResponseEntity.ok(incidenciaService.votarReporte(idUsuarioAutenticado, idReporte, request));
    }

    @GetMapping("/linea/{idLinea}")
    public ResponseEntity<List<ReporteResponseDTO>> listarPorLinea(@PathVariable String idLinea) {
        return ResponseEntity.ok(incidenciaService.listarActivosPorLinea(idLinea));
    }
}