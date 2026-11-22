package com.bypass.backend.service;

import com.bypass.backend.dto.SalidaDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ProximasSalidasService {

    private static final ZoneId MADRID_ZONE = ZoneId.of("Europe/Madrid");
    private final JdbcTemplate jdbcTemplate;

    public List<SalidaDTO> obtenerProximasSalidas(String idEstacion, int limite) {
        ZonedDateTime ahoraMadrid = ZonedDateTime.now(MADRID_ZONE);
        LocalDate hoy = ahoraMadrid.toLocalDate();
        LocalTime horaLocal = ahoraMadrid.toLocalTime();
        int segundosAhora = horaLocal.toSecondOfDay();

        // Si estamos de madrugada (00:00 - 03:59), los trenes nocturnos del servicio anterior
        // suelen figurar con horas >= 24:00:00 (86400s+) del día operativo anterior.
        LocalDate fechaServicio = horaLocal.getHour() < 4 ? hoy.minusDays(1) : hoy;
        int segundosConsulta = horaLocal.getHour() < 4 ? segundosAhora + 86400 : segundosAhora;

        Set<String> serviciosActivos = obtenerServiciosActivos(fechaServicio);
        if (serviciosActivos.isEmpty()) {
            return List.of();
        }

        String placeholders = String.join(",", Collections.nCopies(serviciosActivos.size(), "?"));

        String sql = """
            SELECT
                l.id_linea,
                l.codigo_corto,
                COALESCE(NULLIF(t.headsign, ''), l.nombre_largo) AS destino,
                st.departure_seconds,
                st.arrival_seconds
            FROM gtfs_stop_time st
            JOIN gtfs_trip t ON t.trip_id = st.trip_id
            JOIN linea l ON l.id_linea = t.id_linea
            WHERE st.id_estacion = ?
              AND t.service_id IN (%s)
            ORDER BY COALESCE(st.departure_seconds, st.arrival_seconds, 999999) ASC
            LIMIT 250
            """.formatted(placeholders);

        List<Object> params = new ArrayList<>();
        params.add(idEstacion);
        params.addAll(serviciosActivos);

        var rows = jdbcTemplate.queryForList(sql, params.toArray());
        Map<String, Integer> retrasoVivoPorLinea = obtenerRetrasosEnVivoPorLinea();

        var futuras = new ArrayList<SalidaCandidata>();

        for (Map<String, Object> row : rows) {
            String idLinea = String.valueOf(row.get("id_linea"));
            String codigoLinea = String.valueOf(row.get("codigo_corto"));
            String destino = row.get("destino") != null ? String.valueOf(row.get("destino")) : codigoLinea;

            Integer segGtfs = extraerSegundosGtfs(row);
            if (segGtfs == null) continue;

            Integer retrasoMinVivo = retrasoVivoPorLinea.get(idLinea);
            boolean enVivo = retrasoMinVivo != null;
            int ajusteSeg = enVivo ? Math.max(0, retrasoMinVivo) * 60 : 0;

            int segReales = segGtfs + ajusteSeg;
            if (segReales < segundosConsulta) continue;

            int diffSeg = segReales - segundosConsulta;
            int minutosRestantes = Math.max(0, diffSeg / 60);
            String horaFormateada = formatearHoraGtfs(segReales);

            futuras.add(new SalidaCandidata(
                    codigoLinea,
                    destino,
                    minutosRestantes,
                    horaFormateada,
                    enVivo ? "EN_VIVO" : "TEORICO",
                    enVivo && retrasoMinVivo >= 4 ? "RETRASADO" : "NORMAL",
                    segReales
            ));
        }

        futuras.sort(Comparator.comparingInt(SalidaCandidata::segundosOrden));

        return futuras.stream()
                .limit(Math.max(1, limite))
                .map(c -> new SalidaDTO(
                        c.linea(),
                        c.destino(),
                        c.minutos(),
                        c.horaSalida(),
                        c.fuente(),
                        c.estado()
                ))
                .toList();
    }

    private Set<String> obtenerServiciosActivos(LocalDate fecha) {
        String diaColumna = switch (fecha.getDayOfWeek()) {
            case MONDAY -> "lunes";
            case TUESDAY -> "martes";
            case WEDNESDAY -> "miercoles";
            case THURSDAY -> "jueves";
            case FRIDAY -> "viernes";
            case SATURDAY -> "sabado";
            case SUNDAY -> "domingo";
        };

        Set<String> activos = new LinkedHashSet<>();

        String sqlCalendar = """
            SELECT service_id
            FROM gtfs_service_calendar
            WHERE %s = true
              AND start_date <= ?
              AND end_date >= ?
            """.formatted(diaColumna);

        Date sqlDate = Date.valueOf(fecha);
        jdbcTemplate.queryForList(sqlCalendar, String.class, sqlDate, sqlDate)
                .forEach(activos::add);

        // Aplicar excepciones: 1 = servicio añadido, 2 = servicio eliminado
        String sqlExceptions = """
            SELECT service_id, exception_type
            FROM gtfs_service_exception
            WHERE fecha = ?
            """;

        for (Map<String, Object> ex : jdbcTemplate.queryForList(sqlExceptions, sqlDate)) {
            Object rawServiceId = ex.get("service_id");
            Object rawTipo = ex.get("exception_type");
            if (rawServiceId == null || !(rawTipo instanceof Number numTipo)) {
                continue;
            }
            String serviceId = String.valueOf(rawServiceId);
            int tipoExcepcion = numTipo.intValue();
            if (tipoExcepcion == 1) {
                activos.add(serviceId);
            } else if (tipoExcepcion == 2) {
                activos.remove(serviceId);
            }
        }

        return activos;
    }

    private Map<String, Integer> obtenerRetrasosEnVivoPorLinea() {
        String sql = """
            SELECT id_linea, MAX(minutos_retraso) AS max_retraso
            FROM tren
            WHERE ultima_actualizacion > CURRENT_TIMESTAMP - INTERVAL '5 minutes'
            GROUP BY id_linea
            """;

        Map<String, Integer> mapa = new HashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql)) {
            if (row.get("id_linea") != null && row.get("max_retraso") != null) {
                mapa.put(String.valueOf(row.get("id_linea")), ((Number) row.get("max_retraso")).intValue());
            }
        }
        return mapa;
    }

    private Integer extraerSegundosGtfs(Map<String, Object> row) {
        Object depSec = row.get("departure_seconds");
        if (depSec instanceof Number n) return n.intValue();

        Object arrSec = row.get("arrival_seconds");
        if (arrSec instanceof Number n) return n.intValue();

        return null;
    }

    private String formatearHoraGtfs(int segundosTotales) {
        int segDia = ((segundosTotales % 86400) + 86400) % 86400;
        int h = segDia / 3600;
        int m = (segDia % 3600) / 60;
        return String.format("%02d:%02d", h, m);
    }

    private record SalidaCandidata(
            String linea,
            String destino,
            int minutos,
            String horaSalida,
            String fuente,
            String estado,
            int segundosOrden
    ) {}
}