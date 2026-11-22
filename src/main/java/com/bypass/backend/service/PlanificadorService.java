package com.bypass.backend.service;

import com.bypass.backend.dto.RutaBusquedaDTO;
import com.bypass.backend.dto.RutaBusquedaDTO.EstacionRef;
import com.bypass.backend.dto.RutaBusquedaDTO.RutaOpcionDTO;
import com.bypass.backend.dto.RutaBusquedaDTO.TramoRutaDTO;
import com.bypass.backend.dto.RutaHabitualEstadoDTO.EstadoResponse;
import com.bypass.backend.dto.RutaHabitualEstadoDTO.TrayectoItem;
import com.bypass.backend.dto.SalidaDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
public class PlanificadorService {

    private final JdbcTemplate jdbcTemplate;
    private final ProximasSalidasService proximasSalidasService;
    private static final ZoneId MADRID_ZONE = ZoneId.of("Europe/Madrid");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    public RutaBusquedaDTO buscarRutas(String idOrigen, String idDestino, boolean soloPmr) {
        EstacionInfo origen = obtenerEstacion(idOrigen);
        EstacionInfo destino = obtenerEstacion(idDestino);
        LocalTime ahora = ZonedDateTime.now(MADRID_ZONE).toLocalTime();

        List<RutaEvaluada> candidatas = new ArrayList<>();

        // 1. Buscar rutas directas en GTFS (Cercanías o Metro)
        candidatas.addAll(buscarTramosDirectos(origen, destino, ahora));

        // 2. Buscar rutas con 1 transbordo vía intercambiador_enlace
        candidatas.addAll(buscarRutasConTransbordo(origen, destino, ahora));

        // 3. Incorporar alternativas curadas de la tabla alternativa_ruta
        candidatas.addAll(buscarAlternativasTabla(origen, destino, ahora));

        if (soloPmr) {
            candidatas = candidatas.stream().filter(r -> r.opcion.accesiblePmr()).toList();
        }

        if (candidatas.isEmpty()) {
            return new RutaBusquedaDTO(
                    new EstacionRef(origen.id, origen.nombre),
                    new EstacionRef(destino.id, destino.nombre),
                    false,
                    null,
                    null,
                    List.of()
            );
        }

        // Ordenar por coste percibido (duración real + penalización transbordos + gravedad incidencias)
        List<RutaEvaluada> ordenadas = new ArrayList<>(candidatas);
        ordenadas.sort(Comparator.comparingInt(r -> r.costePercibido));

        // Eliminar duplicados por combinación de líneas
        List<RutaEvaluada> unicas = new ArrayList<>();
        Set<String> firmas = new HashSet<>();
        for (RutaEvaluada r : ordenadas) {
            String firma = r.opcion.tramos().stream()
                    .map(t -> t.tipo() + ":" + t.linea() + ":" + t.destino())
                    .reduce("", (a, b) -> a + "|" + b);
            if (firmas.add(firma)) {
                unicas.add(r);
            }
        }

        RutaEvaluada ganadora = unicas.get(0);
        RutaEvaluada directaHabitual = unicas.stream()
                .filter(r -> r.opcion.numTransbordos() == 0)
                .findFirst()
                .orElse(null);

        boolean hayBypass = false;
        String mensajeBypass = null;

        if (directaHabitual != null && !directaHabitual.opcion.idRuta().equals(ganadora.opcion.idRuta())) {
            int ahorro = directaHabitual.opcion.duracionTotalMin() - ganadora.opcion.duracionTotalMin();
            if (directaHabitual.bloqueada || ahorro >= 6) {
                hayBypass = true;
                mensajeBypass = directaHabitual.bloqueada
                        ? "Incidencia grave en la línea directa (" + directaHabitual.motivoIncidencia + "). Te recomendamos esta alternativa operativa."
                        : "Demoras detectadas en la ruta directa. Con esta alternativa ahorras " + ahorro + " min.";
            }
        }

        RutaOpcionDTO recomendadaDto = new RutaOpcionDTO(
                ganadora.opcion.idRuta(),
                hayBypass ? "RECOMENDADA_BYPASS" : "RECOMENDADA",
                ganadora.opcion.duracionTotalMin(),
                ganadora.opcion.horaLlegadaEstimada(),
                ganadora.opcion.numTransbordos(),
                ganadora.opcion.accesiblePmr(),
                ganadora.opcion.fiabilidad(),
                0,
                ganadora.opcion.explicacion(),
                ganadora.opcion.tramos()
        );

        List<RutaOpcionDTO> alternativasDto = new ArrayList<>();
        for (int i = 1; i < Math.min(unicas.size(), 4); i++) {
            RutaEvaluada alt = unicas.get(i);
            int diff = alt.opcion.duracionTotalMin() - ganadora.opcion.duracionTotalMin();
            alternativasDto.add(new RutaOpcionDTO(
                    alt.opcion.idRuta(),
                    alt.bloqueada ? "AFECTADA_INCIDENCIA" : "ALTERNATIVA",
                    alt.opcion.duracionTotalMin(),
                    alt.opcion.horaLlegadaEstimada(),
                    alt.opcion.numTransbordos(),
                    alt.opcion.accesiblePmr(),
                    alt.opcion.fiabilidad(),
                    diff,
                    alt.opcion.explicacion(),
                    alt.opcion.tramos()
            ));
        }

        return new RutaBusquedaDTO(
                new EstacionRef(origen.id, origen.nombre),
                new EstacionRef(destino.id, destino.nombre),
                hayBypass,
                mensajeBypass,
                recomendadaDto,
                alternativasDto
        );
    }

    public List<EstadoResponse> evaluarRutasHabituales(List<TrayectoItem> trayectos) {
        if (trayectos == null || trayectos.isEmpty()) return List.of();

        List<EstadoResponse> salida = new ArrayList<>();
        for (TrayectoItem item : trayectos) {
            try {
                RutaBusquedaDTO res = buscarRutas(item.origen(), item.destino(), false);
                if (res.rutaRecomendada() == null) continue;

                int durActual = res.rutaRecomendada().duracionTotalMin();
                int durNormal = res.alternativas().stream()
                        .filter(a -> a.numTransbordos() == 0)
                        .mapToInt(RutaOpcionDTO::duracionTotalMin)
                        .findFirst()
                        .orElse(durActual);

                String estado = res.hayAlertaBypass() ? "ALTERADO" : "NORMAL";
                salida.add(new EstadoResponse(
                        item.id(),
                        res.origen().nombre(),
                        res.destino().nombre(),
                        estado,
                        Math.min(durNormal, durActual),
                        durActual,
                        res.hayAlertaBypass(),
                        res.mensajeBypass()
                ));
            } catch (Exception ignored) {
                // Si una estación guardada ya no existe, se omite sin romper el lote
            }
        }
        return salida;
    }

    private List<RutaEvaluada> buscarTramosDirectos(EstacionInfo origen, EstacionInfo destino, LocalTime ahora) {
        String sql = """
            SELECT DISTINCT ON (l.codigo_corto)
                   l.id_linea,
                   l.codigo_corto,
                   t.trip_id,
                   st1.stop_sequence AS seq_origen,
                   st2.stop_sequence AS seq_destino,
                   GREATEST(2, ROUND((st2.arrival_seconds - st1.departure_seconds) / 60.0))::int AS duracion_viaje_min
            FROM gtfs_stop_time st1
            JOIN gtfs_stop_time st2 ON st1.trip_id = st2.trip_id
            JOIN gtfs_trip t ON t.trip_id = st1.trip_id
            JOIN linea l ON l.id_linea = t.id_linea
            WHERE st1.id_estacion = ?
              AND st2.id_estacion = ?
              AND st1.stop_sequence < st2.stop_sequence
            ORDER BY l.codigo_corto, (st2.arrival_seconds - st1.departure_seconds) ASC
            """;

        var rows = jdbcTemplate.queryForList(sql, origen.id, destino.id);
        List<RutaEvaluada> resultados = new ArrayList<>();

        for (Map<String, Object> row : rows) {
            String idLinea = String.valueOf(row.get("id_linea"));
            String codigoLinea = String.valueOf(row.get("codigo_corto"));
            String tripId = String.valueOf(row.get("trip_id"));
            int seq1 = ((Number) row.get("seq_origen")).intValue();
            int seq2 = ((Number) row.get("seq_destino")).intValue();
            int duracionViaje = ((Number) row.get("duracion_viaje_min")).intValue();

            // Espera estimada según próximas salidas reales de esa línea en origen
            List<SalidaDTO> salidas = proximasSalidasService.obtenerProximasSalidas(origen.id, 6);
            SalidaDTO prox = salidas.stream()
                    .filter(s -> s.linea().equalsIgnoreCase(codigoLinea))
                    .findFirst()
                    .orElse(null);

            int esperaMin = prox != null ? prox.minutos() : 6;
            String fuente = prox != null ? prox.fuente() : (origen.id.startsWith("madrid_metro_") ? "GTFS_FRECUENCIA" : "GTFS_ESTATICO");

            IncidenciaEval inc = evaluarIncidenciaLinea(idLinea, origen.id, destino.id);
            int duracionTotal = esperaMin + duracionViaje + inc.retrasoExtraMin;
            boolean pmr = origen.pmr && destino.pmr;

            List<String> intermedias = obtenerParadasIntermedias(tripId, seq1, seq2);

            TramoRutaDTO tramo = new TramoRutaDTO(
                    1,
                    "TRANSPORTE",
                    origen.id.startsWith("madrid_metro_") ? "METRO_MADRID" : "RENFE_CERCANIAS",
                    codigoLinea,
                    origen.nombre,
                    destino.nombre,
                    duracionViaje + inc.retrasoExtraMin,
                    esperaMin,
                    fuente,
                    inc.estadoTramo,
                    pmr,
                    intermedias
            );

            String fiabilidad = inc.bloqueada ? "BAJA" : ("GTFS_FRECUENCIA".equals(fuente) ? "MEDIA" : "ALTA");
            String explicacion = inc.bloqueada
                    ? "Línea " + codigoLinea + " afectada: " + inc.descripcion
                    : "Trayecto directo en línea " + codigoLinea + " (" + intermedias.size() + " paradas intermedias).";

            RutaOpcionDTO opcion = new RutaOpcionDTO(
                    "dir_" + codigoLinea,
                    "DIRECTA",
                    duracionTotal,
                    ahora.plusMinutes(duracionTotal).format(HH_MM),
                    0,
                    pmr,
                    fiabilidad,
                    null,
                    explicacion,
                    List.of(tramo)
            );

            int coste = duracionTotal + (inc.bloqueada ? 500 : inc.retrasoExtraMin * 2);
            resultados.add(new RutaEvaluada(opcion, coste, inc.bloqueada, inc.descripcion));
        }
        return resultados;
    }

    private List<RutaEvaluada> buscarRutasConTransbordo(EstacionInfo origen, EstacionInfo destino, LocalTime ahora) {
        var enlaces = jdbcTemplate.queryForList(
                "SELECT id_estacion_origen, id_estacion_destino, minutos_caminando, accesible_pmr, descripcion_paso FROM intercambiador_enlace"
        );

        List<RutaEvaluada> combinaciones = new ArrayList<>();

        for (Map<String, Object> enlace : enlaces) {
            String nodoA = String.valueOf(enlace.get("id_estacion_origen"));
            String nodoB = String.valueOf(enlace.get("id_estacion_destino"));
            if (nodoA.equals(origen.id) || nodoB.equals(destino.id)) continue;

            int minPie = ((Number) enlace.get("minutos_caminando")).intValue();
            boolean enlacePmr = Boolean.TRUE.equals(enlace.get("accesible_pmr"));

            EstacionInfo estNodoA = obtenerEstacionOpcional(nodoA);
            EstacionInfo estNodoB = obtenerEstacionOpcional(nodoB);
            if (estNodoA == null || estNodoB == null) continue;

            var tramos1 = buscarTramosDirectos(origen, estNodoA, ahora);
            if (tramos1.isEmpty()) continue;

            var tramos2 = buscarTramosDirectos(estNodoB, destino, ahora.plusMinutes(tramos1.get(0).opcion.duracionTotalMin() + minPie));
            if (tramos2.isEmpty()) continue;

            RutaEvaluada mejorT1 = tramos1.get(0);
            RutaEvaluada mejorT2 = tramos2.get(0);

            TramoRutaDTO t1 = mejorT1.opcion.tramos().get(0);
            TramoRutaDTO tPie = new TramoRutaDTO(
                    2,
                    "TRANSBORDO_PIE",
                    "INTERCAMBIADOR",
                    "PIE",
                    estNodoA.nombre,
                    estNodoB.nombre,
                    minPie,
                    0,
                    "INTERCAMBIADOR",
                    "NORMAL",
                    enlacePmr,
                    List.of()
            );
            TramoRutaDTO t2Original = mejorT2.opcion.tramos().get(0);
            TramoRutaDTO t2 = new TramoRutaDTO(
                    3,
                    t2Original.tipo(),
                    t2Original.operador(),
                    t2Original.linea(),
                    t2Original.origen(),
                    t2Original.destino(),
                    t2Original.duracionMin(),
                    t2Original.esperaMin(),
                    t2Original.fuenteDato(),
                    t2Original.estado(),
                    t2Original.accesiblePmr(),
                    t2Original.paradasIntermedias()
            );

            int duracionTotal = mejorT1.opcion.duracionTotalMin() + minPie + mejorT2.opcion.duracionTotalMin();
            boolean pmrTotal = mejorT1.opcion.accesiblePmr() && enlacePmr && mejorT2.opcion.accesiblePmr();
            boolean bloqueada = mejorT1.bloqueada || mejorT2.bloqueada;

            String explicacion = "Combina línea " + t1.linea() + " hasta " + estNodoA.nombre +
                    " y transbordo a línea " + t2.linea() + " (" + minPie + " min a pie).";

            RutaOpcionDTO opcion = new RutaOpcionDTO(
                    "trans_" + t1.linea() + "_" + t2.linea(),
                    "CON_TRANSBORDO",
                    duracionTotal,
                    ahora.plusMinutes(duracionTotal).format(HH_MM),
                    1,
                    pmrTotal,
                    bloqueada ? "BAJA" : "ALTA",
                    null,
                    explicacion,
                    List.of(t1, tPie, t2)
            );

            int coste = duracionTotal + 5 + (bloqueada ? 500 : 0);
            combinaciones.add(new RutaEvaluada(opcion, coste, bloqueada, null));
        }
        return combinaciones;
    }

    private List<RutaEvaluada> buscarAlternativasTabla(EstacionInfo origen, EstacionInfo destino, LocalTime ahora) {
        var rows = jdbcTemplate.queryForList(
                "SELECT id_alternativa, modo_transporte, linea_alternativa, tiempo_estimado_min, instrucciones FROM alternativa_ruta WHERE id_estacion_origen = ? AND id_estacion_destino = ?",
                origen.id, destino.id
        );
        List<RutaEvaluada> lista = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            int tiempo = ((Number) row.get("tiempo_estimado_min")).intValue();
            String modo = String.valueOf(row.get("modo_transporte"));
            String linea = String.valueOf(row.get("linea_alternativa"));
            String instr = String.valueOf(row.get("instrucciones"));

            TramoRutaDTO tramo = new TramoRutaDTO(
                    1, "TRANSPORTE", modo, linea, origen.nombre, destino.nombre,
                    tiempo, 4, "ALTERNATIVA_CURADA", "NORMAL", true, List.of()
            );
            RutaOpcionDTO opcion = new RutaOpcionDTO(
                    "alt_db_" + row.get("id_alternativa"),
                    "ALTERNATIVA_RESPALDO",
                    tiempo + 4,
                    ahora.plusMinutes(tiempo + 4L).format(HH_MM),
                    0,
                    true,
                    "MEDIA",
                    null,
                    instr,
                    List.of(tramo)
            );
            lista.add(new RutaEvaluada(opcion, tiempo + 6, false, null));
        }
        return lista;
    }

    private IncidenciaEval evaluarIncidenciaLinea(String idLinea, String idOrigen, String idDestino) {
        // 1. Alertas oficiales o reportes verificados en reporte_incidencia
        String sqlReportes = """
            SELECT tipo_incidencia, descripcion, estado_verificacion
            FROM reporte_incidencia
            WHERE expira_en > CURRENT_TIMESTAMP
              AND estado_verificacion IN ('CONFIRMADO', 'PENDIENTE')
              AND (id_linea = ? OR id_estacion IN (?, ?))
            ORDER BY CASE estado_verificacion WHEN 'CONFIRMADO' THEN 1 ELSE 2 END, creado_en DESC
            LIMIT 1
            """;

        var rows = jdbcTemplate.queryForList(sqlReportes, idLinea, idOrigen, idDestino);
        if (!rows.isEmpty()) {
            Map<String, Object> inc = rows.get(0);
            String tipo = String.valueOf(inc.get("tipo_incidencia"));
            String desc = inc.get("descripcion") != null ? String.valueOf(inc.get("descripcion")) : tipo;
            boolean confirmada = "CONFIRMADO".equals(String.valueOf(inc.get("estado_verificacion")));

            return switch (tipo) {
                case "CORTE_VIA", "AVERIA_TREN", "INFRAESTRUCTURA" ->
                        new IncidenciaEval(true, confirmada ? 30 : 15, "INCIDENCIA_GRAVE", desc);
                case "RETRASO" ->
                        new IncidenciaEval(false, confirmada ? 12 : 6, "RETRASOS", desc);
                default ->
                        new IncidenciaEval(false, 4, "AVISO", desc);
            };
        }

        // 2. Telemetría en tiempo real de GTFS-RT (tabla tren actualizada en los últimos 10 min)
        String sqlTrenesRt = """
            SELECT COALESCE(MAX(minutos_retraso), 0) AS max_retraso,
                   COUNT(*) FILTER (WHERE estado = 'DETENIDO') AS detenidos
            FROM tren
            WHERE id_linea = ?
              AND ultima_actualizacion > CURRENT_TIMESTAMP - INTERVAL '10 minutes'
            """;

        var rtRows = jdbcTemplate.queryForList(sqlTrenesRt, idLinea);
        if (!rtRows.isEmpty()) {
            int maxRetraso = ((Number) rtRows.get(0).get("max_retraso")).intValue();
            int detenidos = ((Number) rtRows.get(0).get("detenidos")).intValue();

            if (detenidos > 0 || maxRetraso >= 15) {
                return new IncidenciaEval(true, Math.max(maxRetraso, 20), "INCIDENCIA_GRAVE",
                        "Demoras graves en tiempo real (+" + maxRetraso + " min en GTFS-RT)");
            }
            if (maxRetraso >= 5) {
                return new IncidenciaEval(false, maxRetraso, "RETRASOS",
                        "Retrasos detectados en tiempo real (+" + maxRetraso + " min)");
            }
        }

        return new IncidenciaEval(false, 0, "NORMAL", null);
    }

    private List<String> obtenerParadasIntermedias(String tripId, int seqOrigen, int seqDestino) {
        return jdbcTemplate.queryForList(
                """
                SELECT e.nombre
                FROM gtfs_stop_time st
                JOIN estacion e ON e.id_estacion = st.id_estacion
                WHERE st.trip_id = ?
                  AND st.stop_sequence > ?
                  AND st.stop_sequence < ?
                ORDER BY st.stop_sequence ASC
                """,
                String.class, tripId, seqOrigen, seqDestino
        );
    }

    private EstacionInfo obtenerEstacion(String idEstacion) {
        EstacionInfo info = obtenerEstacionOpcional(idEstacion);
        if (info == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Estación no encontrada: " + idEstacion);
        }
        return info;
    }

    private EstacionInfo obtenerEstacionOpcional(String idEstacion) {
        var rows = jdbcTemplate.queryForList(
                "SELECT id_estacion, nombre, accesible_pmr FROM estacion WHERE id_estacion = ?",
                idEstacion
        );
        if (rows.isEmpty()) return null;
        var r = rows.get(0);
        return new EstacionInfo(
                String.valueOf(r.get("id_estacion")),
                String.valueOf(r.get("nombre")),
                Boolean.TRUE.equals(r.get("accesible_pmr"))
        );
    }

    private record EstacionInfo(String id, String nombre, boolean pmr) {}
    private record IncidenciaEval(boolean bloqueada, int retrasoExtraMin, String estadoTramo, String descripcion) {}
    private record RutaEvaluada(RutaOpcionDTO opcion, int costePercibido, boolean bloqueada, String motivoIncidencia) {}
}