package com.bypass.backend.controller;

import com.bypass.backend.dto.*;
import com.bypass.backend.service.IncidenciaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/incidencias")
@RequiredArgsConstructor
public class IncidenciaController {

    private final IncidenciaService incidenciaService;

    @PostMapping
    public ResponseEntity<ReporteResponseDTO> crear(@Valid @RequestBody CrearReporteRequest request) {
        return ResponseEntity.ok(incidenciaService.crearReporte(request));
    }

    @PostMapping("/{idReporte}/votar")
    public ResponseEntity<ReporteResponseDTO> votar(
            @PathVariable Long idReporte,
            @Valid @RequestBody VotarReporteRequest request) {
        return ResponseEntity.ok(incidenciaService.votarReporte(idReporte, request));
    }

    @GetMapping("/linea/{idLinea}")
    public ResponseEntity<List<ReporteResponseDTO>> listarPorLinea(@PathVariable String idLinea) {
        return ResponseEntity.ok(incidenciaService.listarActivosPorLinea(idLinea));
    }
}