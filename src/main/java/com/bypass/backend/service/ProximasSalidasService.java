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
        // suelen figurar en GTFS con horas >= 24:00:00 (86400s+) del día operativo anterior.
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
                COALESCE(NULLIF(t.trip_headsign, ''), l.nombre_largo) AS destino,
                st.departure_time,
                st.arrival_time,
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
            case MONDAY -> "monday";
            case TUESDAY -> "tuesday";
            case WEDNESDAY -> "wednesday";
            case THURSDAY -> "thursday";
            case FRIDAY -> "friday";
            case SATURDAY -> "saturday";
            case SUNDAY -> "sunday";
        };

        Set<String> activos = new LinkedHashSet<>();

        // Soporta start_date / end_date guardados como YYYYMMDD (INTEGER/VARCHAR) o como DATE
        String sqlCalendar = """
            SELECT service_id
            FROM gtfs_service_calendar
            WHERE %s = 1
              AND CAST(REPLACE(CAST(start_date AS TEXT), '-', '') AS INTEGER) <= ?
              AND CAST(REPLACE(CAST(end_date AS TEXT), '-', '') AS INTEGER) >= ?
            """.formatted(diaColumna);

        int fechaYyyyMmDd = fecha.getYear() * 10000 + fecha.getMonthValue() * 100 + fecha.getDayOfMonth();
        jdbcTemplate.queryForList(sqlCalendar, String.class, fechaYyyyMmDd, fechaYyyyMmDd)
                .forEach(activos::add);

        // Aplicar excepciones: 1 = servicio añadido, 2 = servicio eliminado
        String sqlExceptions = """
            SELECT service_id, exception_type
            FROM gtfs_service_exception
            WHERE CAST(REPLACE(CAST(date AS TEXT), '-', '') AS INTEGER) = ?
            """;

        for (Map<String, Object> ex : jdbcTemplate.queryForList(sqlExceptions, fechaYyyyMmDd)) {
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

        String horaTexto = row.get("departure_time") != null
                ? String.valueOf(row.get("departure_time"))
                : (row.get("arrival_time") != null ? String.valueOf(row.get("arrival_time")) : null);

        if (horaTexto == null || horaTexto.isBlank()) return null;
        return parsearHoraGtfsASegundos(horaTexto.trim());
    }

    public static int parsearHoraGtfsASegundos(String hhmmss) {
        String[] partes = hhmmss.split(":");
        if (partes.length < 2) throw new IllegalArgumentException("Hora GTFS inválida: " + hhmmss);
        int horas = Integer.parseInt(partes[0]);
        int minutos = Integer.parseInt(partes[1]);
        int segundos = partes.length >= 3 ? Integer.parseInt(partes[2]) : 0;
        return horas * 3600 + minutos * 60 + segundos;
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