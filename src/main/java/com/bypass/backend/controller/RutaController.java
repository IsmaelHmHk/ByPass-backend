package com.bypass.backend.controller;

import com.bypass.backend.dto.AlternativaRutaDTO;
import com.bypass.backend.dto.RutaBusquedaDTO;
import com.bypass.backend.dto.RutaHabitualEstadoDTO.ConsultaRequest;
import com.bypass.backend.dto.RutaHabitualEstadoDTO.EstadoResponse;
import com.bypass.backend.repository.AlternativaRutaRepository;
import com.bypass.backend.service.EnrutamientoService;
import com.bypass.backend.service.PlanificadorService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/rutas")
@RequiredArgsConstructor
@Validated
public class RutaController {

    private final AlternativaRutaRepository alternativaRepo;
    private final EnrutamientoService enrutamientoService;
    private final PlanificadorService planificadorService;

    @GetMapping("/alternativas")
    public ResponseEntity<?> buscarAlternativas(
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{2,32}$") String origen,
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{2,32}$") String destino) {

        List<AlternativaRutaDTO> resultados = alternativaRepo
                .findByEstacionOrigenIdEstacionAndEstacionDestinoIdEstacionOrderByTiempoEstimadoMinAsc(origen, destino)
                .stream()
                .map(a -> new AlternativaRutaDTO(
                        a.getIdAlternativa(),
                        a.getEstacionOrigen().getNombre(),
                        a.getEstacionDestino().getNombre(),
                        a.getModoTransporte(),
                        a.getLineaAlternativa(),
                        a.getTiempoEstimadoMin(),
                        a.getInstrucciones()
                ))
                .toList();

        return ResponseEntity.ok(resultados);
    }

    @GetMapping("/calcular")
    public ResponseEntity<?> calcularRutaDinamica(
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{2,32}$") String origen,
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{2,32}$") String destino) {
        return ResponseEntity.ok(enrutamientoService.calcularMejorRuta(origen, destino));
    }

    @GetMapping("/buscar")
    public RutaBusquedaDTO buscarRutas(
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{2,64}$") String origen,
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{2,64}$") String destino,
            @RequestParam(defaultValue = "false") boolean soloPmr) {
        return planificadorService.buscarRutas(origen, destino, soloPmr);
    }

    @PostMapping("/estado-habituales")
    public List<EstadoResponse> obtenerEstadoHabituales(@RequestBody ConsultaRequest request) {
        return planificadorService.evaluarRutasHabituales(request != null ? request.trayectos() : List.of());
    }
}