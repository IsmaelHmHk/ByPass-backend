package com.bypass.backend.controller;

import com.bypass.backend.dto.EstacionDetalleDTO;
import com.bypass.backend.dto.EstacionParadaDTO;
import com.bypass.backend.dto.LineaDTO;
import com.bypass.backend.repository.EstacionRepository;
import com.bypass.backend.repository.LineaEstacionRepository;
import com.bypass.backend.repository.LineaRepository;
import com.bypass.backend.service.EstacionDetalleService;
import com.bypass.backend.service.ProximasSalidasService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/catalogo")
@RequiredArgsConstructor
public class CatalogoController {

    private final EstacionDetalleService estacionDetalleService;
    private final LineaRepository lineaRepo;
    private final EstacionRepository estacionRepo;
    private final LineaEstacionRepository lineaEstacionRepo;
    private final ProximasSalidasService proximasSalidasService;

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
    @GetMapping("/estaciones/{idEstacion}/detalle")
    public EstacionDetalleDTO obtenerDetalleEstacion(@PathVariable String idEstacion) {
        return estacionDetalleService.obtenerDetalle(idEstacion);
    }

    @GetMapping("/cercanas")
    public ResponseEntity<?> obtenerParadasCercanas(
            @RequestParam double lat,
            @RequestParam double lon,
            @RequestParam(defaultValue = "2000") double radioMetros) {

        var cercanas = estacionRepo.findAll().stream()
                .map(est -> {
                    long dist = Math.round(calcularDistanciaMetros(
                            lat, lon,
                            est.getLatitud().doubleValue(),
                            est.getLongitud().doubleValue()
                    ));
                    int minutosPie = (int) Math.max(1, Math.ceil(dist / 80.0));
                    var lineas = lineaEstacionRepo.findByEstacionIdEstacion(est.getIdEstacion())
                            .stream()
                            .map(le -> le.getLinea().getCodigoCorto())
                            .distinct()
                            .toList();

                    var proximasSalidas = proximasSalidasService.obtenerProximasSalidas(est.getIdEstacion(), 3);

                    Map<String, Object> dto = new LinkedHashMap<>();
                    dto.put("idEstacion", est.getIdEstacion());
                    dto.put("codigoParada", est.getCodigoParada() != null ? est.getCodigoParada() : est.getIdEstacion());
                    dto.put("nombre", est.getNombre());
                    dto.put("latitud", est.getLatitud());
                    dto.put("longitud", est.getLongitud());
                    dto.put("accesiblePmr", est.getAccesiblePmr() != null ? est.getAccesiblePmr() : true);
                    dto.put("distanciaMetros", dist);
                    dto.put("minutosCaminando", minutosPie);
                    dto.put("lineas", lineas);
                    dto.put("proximasSalidas", proximasSalidas);
                    return dto;
                })
                .filter(m -> (Long) m.get("distanciaMetros") <= radioMetros)
                .sorted(Comparator.comparingLong(m -> (Long) m.get("distanciaMetros")))
                .limit(5)
                .toList();

        return ResponseEntity.ok(cercanas);
    }


    private double calcularDistanciaMetros(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
