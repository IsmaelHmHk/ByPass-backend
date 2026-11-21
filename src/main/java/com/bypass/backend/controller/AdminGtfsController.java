package com.bypass.backend.controller;

import com.bypass.backend.service.GtfsImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/v1/admin/gtfs")
@RequiredArgsConstructor
public class AdminGtfsController {

    private final GtfsImportService gtfsImportService;

    @PostMapping("/importar-madrid")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> importarMadrid() throws IOException {
        Path rutaLocalSegura = Path.of("data/gtfs").toAbsolutePath().normalize();
        return ResponseEntity.ok(gtfsImportService.importarNucleoMadridDesdeDirectorio(rutaLocalSegura));
    }
}