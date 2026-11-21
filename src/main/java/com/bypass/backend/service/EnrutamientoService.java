package com.bypass.backend.service;

import com.bypass.backend.dto.AlternativaRutaDTO;
import com.bypass.backend.dto.PasoRutaDTO;
import com.bypass.backend.dto.RutaCalculadaDTO;
import com.bypass.backend.entity.Estacion;
import com.bypass.backend.entity.Linea;
import com.bypass.backend.entity.LineaEstacion;
import com.bypass.backend.entity.ReporteIncidencia;
import com.bypass.backend.repository.AlternativaRutaRepository;
import com.bypass.backend.repository.EstacionRepository;
import com.bypass.backend.repository.LineaEstacionRepository;
import com.bypass.backend.repository.LineaRepository;
import com.bypass.backend.repository.ReporteIncidenciaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class EnrutamientoService {

    private final LineaRepository lineaRepo;
    private final EstacionRepository estacionRepo;
    private final LineaEstacionRepository lineaEstacionRepo;
    private final ReporteIncidenciaRepository reporteRepo;
    private final AlternativaRutaRepository alternativaRepo;

    private static final int PENALIZACION_INCIDENCIA_MIN = 25;
    private static final int COSTE_TRANSBORDO_MIN = 5;

    private record Arista(String destino, String idLinea, String codigoLinea, int tiempoBaseMin) {}

    private record EstadoNodo(String estacion, String lineaActual, int costeAcumulado, List<PasoRutaDTO> camino)
            implements Comparable<EstadoNodo> {
        @Override
        public int compareTo(EstadoNodo o) {
            return Integer.compare(this.costeAcumulado, o.costeAcumulado);
        }
    }

    @Transactional(readOnly = true)
    public RutaCalculadaDTO calcularMejorRuta(String idOrigen, String idDestino) {
        if (idOrigen.equals(idDestino)) {
            throw new IllegalArgumentException("La estación de origen y destino no pueden ser la misma.");
        }

        Estacion origen = estacionRepo.findById(idOrigen)
                .orElseThrow(() -> new IllegalArgumentException("Estación de origen no encontrada."));
        Estacion destino = estacionRepo.findById(idDestino)
                .orElseThrow(() -> new IllegalArgumentException("Estación de destino no encontrada."));

        // 1. Detectar líneas de Madrid con incidencias activas en este momento
        Set<String> lineasAfectadas = obtenerLineasConIncidenciasActivas();

        // 2. Construir grafo bidireccional de todas las líneas cargadas
        Map<String, List<Arista>> grafo = construirGrafoMadrid();

        // 3. Ejecutar Dijkstra penalizando líneas con incidencias y transbordos
        RutaCalculadaDTO rutaFerroviaria = ejecutarDijkstra(
                origen.getIdEstacion(),
                destino.getIdEstacion(),
                grafo,
                lineasAfectadas
        );

        // 4. Obtener alternativas intermodales (Metro / Bus) entre ambas estaciones
        List<AlternativaRutaDTO> intermodales = alternativaRepo
                .findByEstacionOrigenIdEstacionAndEstacionDestinoIdEstacionOrderByTiempoEstimadoMinAsc(idOrigen, idDestino)
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

        return new RutaCalculadaDTO(
                rutaFerroviaria.tipoRuta(),
                rutaFerroviaria.tiempoTotalMin(),
                rutaFerroviaria.transbordos(),
                rutaFerroviaria.evitaIncidencias(),
                rutaFerroviaria.pasos(),
                intermodales
        );
    }

    private Set<String> obtenerLineasConIncidenciasActivas() {
        OffsetDateTime ahora = OffsetDateTime.now();
        Set<String> afectadas = new HashSet<>();
        for (Linea linea : lineaRepo.findAll()) {
            List<ReporteIncidencia> activos = reporteRepo
                    .findByLineaIdLineaAndExpiraEnAfterAndEstadoVerificacionInOrderByCreadoEnDesc(
                            linea.getIdLinea(),
                            ahora,
                            List.of("CONFIRMADO")
                    );
            if (!activos.isEmpty()) {
                afectadas.add(linea.getIdLinea());
            }
        }
        return afectadas;
    }

    private Map<String, List<Arista>> construirGrafoMadrid() {
        Map<String, List<Arista>> grafo = new HashMap<>();

        for (Linea linea : lineaRepo.findAll()) {
            List<LineaEstacion> paradas = lineaEstacionRepo.findRecorridoPorLinea(linea.getIdLinea());
            for (int i = 0; i < paradas.size() - 1; i++) {
                LineaEstacion actual = paradas.get(i);
                LineaEstacion siguiente = paradas.get(i + 1);

                String estA = actual.getEstacion().getIdEstacion();
                String estB = siguiente.getEstacion().getIdEstacion();
                int tiempo = actual.getTiempoSiguienteMin() != null ? actual.getTiempoSiguienteMin() : 3;

                grafo.computeIfAbsent(estA, k -> new ArrayList<>())
                        .add(new Arista(estB, linea.getIdLinea(), linea.getCodigoCorto(), tiempo));
                grafo.computeIfAbsent(estB, k -> new ArrayList<>())
                        .add(new Arista(estA, linea.getIdLinea(), linea.getCodigoCorto(), tiempo));
            }
        }
        return grafo;
    }

    private RutaCalculadaDTO ejecutarDijkstra(
            String idOrigen,
            String idDestino,
            Map<String, List<Arista>> grafo,
            Set<String> lineasAfectadas) {

        PriorityQueue<EstadoNodo> cola = new PriorityQueue<>();
        Map<String, Integer> mejorCoste = new HashMap<>();

        cola.add(new EstadoNodo(idOrigen, null, 0, List.of()));
        mejorCoste.put(idOrigen + ":NONE", 0);

        while (!cola.isEmpty()) {
            EstadoNodo actual = cola.poll();

            if (actual.estacion().equals(idDestino)) {
                int tiempoReal = actual.camino().stream().mapToInt(PasoRutaDTO::tiempoEstimadoMin).sum();
                int transbordos = calcularTransbordos(actual.camino());
                boolean limpia = actual.camino().stream().noneMatch(PasoRutaDTO::lineaConIncidencias);

                return new RutaCalculadaDTO(
                        limpia ? "CERCANIAS_OPTIMA" : "CERCANIAS_CON_AVISO",
                        tiempoReal + (transbordos * COSTE_TRANSBORDO_MIN),
                        transbordos,
                        limpia,
                        actual.camino(),
                        List.of()
                );
            }

            for (Arista arista : grafo.getOrDefault(actual.estacion(), List.of())) {
                boolean afectada = lineasAfectadas.contains(arista.idLinea());
                int penalizacion = afectada ? PENALIZACION_INCIDENCIA_MIN : 0;
                int costeTransbordo = (actual.lineaActual() != null && !actual.lineaActual().equals(arista.idLinea()))
                        ? COSTE_TRANSBORDO_MIN : 0;

                int nuevoCoste = actual.costeAcumulado() + arista.tiempoBaseMin() + penalizacion + costeTransbordo;
                String claveEstado = arista.destino() + ":" + arista.idLinea();

                if (nuevoCoste < mejorCoste.getOrDefault(claveEstado, Integer.MAX_VALUE)) {
                    mejorCoste.put(claveEstado, nuevoCoste);

                    List<PasoRutaDTO> nuevoCamino = new ArrayList<>(actual.camino());
                    nuevoCamino.add(new PasoRutaDTO(
                            arista.idLinea(),
                            arista.codigoLinea(),
                            actual.estacion(),
                            arista.destino(),
                            arista.tiempoBaseMin(),
                            afectada
                    ));
                    cola.add(new EstadoNodo(arista.destino(), arista.idLinea(), nuevoCoste, nuevoCamino));
                }
            }
        }

        return new RutaCalculadaDTO("SIN_CONEXION_DIRECTA", 0, 0, false, List.of(), List.of());
    }

    private int calcularTransbordos(List<PasoRutaDTO> pasos) {
        if (pasos.size() <= 1) return 0;
        int transbordos = 0;
        for (int i = 1; i < pasos.size(); i++) {
            if (!pasos.get(i).idLinea().equals(pasos.get(i - 1).idLinea())) {
                transbordos++;
            }
        }
        return transbordos;
    }
}