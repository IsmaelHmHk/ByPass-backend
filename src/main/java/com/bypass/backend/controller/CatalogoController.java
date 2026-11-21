package com.bypass.backend.controller;

import com.bypass.backend.dto.EstacionParadaDTO;
import com.bypass.backend.dto.LineaDTO;
import com.bypass.backend.repository.EstacionRepository;
import com.bypass.backend.repository.LineaEstacionRepository;
import com.bypass.backend.repository.LineaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/catalogo")
@RequiredArgsConstructor
public class CatalogoController {

    private final LineaRepository lineaRepo;
    private final EstacionRepository estacionRepo;
    private final LineaEstacionRepository lineaEstacionRepo;

    @GetMapping("/lineas")
    public ResponseEntity<List<LineaDTO>> listarLineas() {
        List<LineaDTO> lineas = lineaRepo.findAll().stream()
                .map(l -> new LineaDTO(l.getIdLinea(), l.getCodigoCorto(), l.getNombreLargo(), l.getColorHex()))
                .toList();
        return ResponseEntity.ok(lineas);
    }

    @GetMapping("/lineas/{idLinea}/estaciones")
    public ResponseEntity<List<EstacionParadaDTO>> listarEstacionesDeLinea(@PathVariable String idLinea) {
        if (!lineaRepo.existsById(idLinea)) {
            return ResponseEntity.notFound().build();
        }
        List<EstacionParadaDTO> paradas = lineaEstacionRepo.findRecorridoPorLinea(idLinea).stream()
                .map(le -> new EstacionParadaDTO(
                        le.getEstacion().getIdEstacion(),
                        le.getEstacion().getNombre(),
                        le.getOrdenParada(),
                        le.getTiempoSiguienteMin(),
                        le.getEstacion().getLatitud(),
                        le.getEstacion().getLongitud(),
                        le.getEstacion().getTieneEnlaceMetro(),
                        le.getEstacion().getTieneEnlaceBus()
                ))
                .toList();
        return ResponseEntity.ok(paradas);
    }

    @GetMapping("/estaciones")
    public ResponseEntity<List<EstacionParadaDTO>> listarTodasEstacionesMadrid() {
        List<EstacionParadaDTO> estaciones = estacionRepo.findByNucleoOrderByNombreAsc("MADRID").stream()
                .map(e -> new EstacionParadaDTO(
                        e.getIdEstacion(),
                        e.getNombre(),
                        null,
                        null,
                        e.getLatitud(),
                        e.getLongitud(),
                        e.getTieneEnlaceMetro(),
                        e.getTieneEnlaceBus()
                ))
                .toList();
        return ResponseEntity.ok(estaciones);
    }
}