package com.bypass.backend.controller;

import com.bypass.backend.dto.AlternativaRutaDTO;
import com.bypass.backend.repository.AlternativaRutaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/rutas")
@RequiredArgsConstructor
public class RutaController {

    private final AlternativaRutaRepository alternativaRepo;

    @GetMapping("/alternativas")
    public ResponseEntity<List<AlternativaRutaDTO>> buscarAlternativas(
            @RequestParam String origen,
            @RequestParam String destino) {

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
}