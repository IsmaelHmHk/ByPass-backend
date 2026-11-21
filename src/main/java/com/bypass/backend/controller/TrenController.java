package com.bypass.backend.controller;

import com.bypass.backend.dto.ReporteVagonRequest;
import com.bypass.backend.dto.TrenActivoDTO;
import com.bypass.backend.dto.VagonEstadoDTO;
import com.bypass.backend.entity.OcupacionVagon;
import com.bypass.backend.entity.Tren;
import com.bypass.backend.repository.OcupacionVagonRepository;
import com.bypass.backend.repository.TrenRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/trenes")
@RequiredArgsConstructor
public class TrenController {

    private final TrenRepository trenRepository;
    private final OcupacionVagonRepository ocupacionVagonRepository;

    @GetMapping("/{idTren}/vagones")
    public ResponseEntity<List<VagonEstadoDTO>> obtenerTermometroVagones(@PathVariable String idTren) {
        if (!trenRepository.existsById(idTren)) {
            return ResponseEntity.notFound().build();
        }

        List<VagonEstadoDTO> vagones = ocupacionVagonRepository
                .findByTrenIdTrenOrderByNumeroVagonAsc(idTren)
                .stream()
                .map(v -> new VagonEstadoDTO(
                        v.getNumeroVagon(),
                        v.getNivelSaturacion(),
                        v.getScorePonderado(),
                        v.getActualizadoEn()
                ))
                .toList();

        return ResponseEntity.ok(vagones);
    }

    @PostMapping("/{idTren}/vagones/reportar")
    public ResponseEntity<VagonEstadoDTO> reportarSaturacionVagon(
            @PathVariable String idTren,
            @Valid @RequestBody ReporteVagonRequest request) {

        Tren tren = trenRepository.findById(idTren).orElse(null);
        if (tren == null || request.numeroVagon() > tren.getNumVagones()) {
            return ResponseEntity.badRequest().build();
        }

        OcupacionVagon vagon = ocupacionVagonRepository
                .findByTrenIdTrenAndNumeroVagon(idTren, request.numeroVagon())
                .orElseGet(() -> OcupacionVagon.builder()
                        .tren(tren)
                        .numeroVagon(request.numeroVagon())
                        .build());

        vagon.setNivelSaturacion(request.nivelSaturacion());
        vagon.setScorePonderado(calcularScoreBase(request.nivelSaturacion()));
        vagon.setActualizadoEn(OffsetDateTime.now());

        OcupacionVagon guardado = ocupacionVagonRepository.save(vagon);

        return ResponseEntity.ok(new VagonEstadoDTO(
                guardado.getNumeroVagon(),
                guardado.getNivelSaturacion(),
                guardado.getScorePonderado(),
                guardado.getActualizadoEn()
        ));
    }
    @GetMapping("/linea/{idLinea}")
    public ResponseEntity<?> listarTrenesPorLinea(@PathVariable String idLinea) {
        java.time.OffsetDateTime ahora = java.time.OffsetDateTime.now();
        List<TrenActivoDTO> trenes = trenRepository.findByLineaIdLinea(idLinea).stream()
                .map(t -> {
                    long segundos = t.getUltimaActualizacion() != null
                            ? java.time.Duration.between(t.getUltimaActualizacion(), ahora).getSeconds()
                            : 9999L;
                    String fuente = segundos <= 120 ? "EN_VIVO_GTFS_RT" : "TEORICO_DEGRADADO";
                    return new TrenActivoDTO(
                            t.getIdTren(),
                            t.getLinea().getIdLinea(),
                            t.getDestino(),
                            t.getNumVagones(),
                            t.getEstado(),
                            t.getMinutosRetraso(),
                            fuente,
                            Math.max(0, segundos),
                            t.getUltimaActualizacion()
                    );
                })
                .toList();
        return ResponseEntity.ok(trenes);
    }
    private BigDecimal calcularScoreBase(String nivel) {
        return switch (nivel) {
            case "VACIO" -> new BigDecimal("15.00");
            case "MODERADO" -> new BigDecimal("45.00");
            case "LLENO" -> new BigDecimal("80.00");
            case "COLAPSADO" -> new BigDecimal("98.00");
            default -> BigDecimal.ZERO;
        };
    }
}