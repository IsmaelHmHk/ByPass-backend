package com.bypass.backend.controller;

import com.bypass.backend.service.GtfsStaticImporterService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/gtfs")
@RequiredArgsConstructor
public class AdminGtfsController {

    private final GtfsStaticImporterService importerService;

    @PostMapping(value = "/importar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> importarZipGtfs(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "renfe_") String prefijo,
            @RequestParam(defaultValue = "RENFE_CERCANIAS") String operador) throws Exception {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "El archivo ZIP GTFS está vacío"));
        }

        Map<String, Integer> resumen = importerService.importarStopsYRoutes(
                file.getInputStream(),
                prefijo,
                operador
        );
        return ResponseEntity.ok(resumen);
    }
}