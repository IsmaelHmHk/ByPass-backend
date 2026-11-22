package com.bypass.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class GtfsStaticImporterService {

    private final JdbcTemplate jdbcTemplate;
    private static final int BATCH_SIZE = 2000;
    private static final DateTimeFormatter GTFS_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Transactional
    public Map<String, Integer> importarStopsYRoutes(
            InputStream zipStream,
            String prefijo,
            String operador,
            String nucleo,
            String filtroRouteIdPrefix) throws Exception {

        Map<String, byte[]> archivos = leerZip(zipStream, Set.of(
                "routes.txt", "trips.txt", "stop_times.txt", "stops.txt"
        ));

        Set<String> rutasValidas = procesarRoutes(archivos.get("routes.txt"), prefijo, operador, filtroRouteIdPrefix);
        Set<String> tripsValidos = extraerTripsValidos(archivos.get("trips.txt"), rutasValidas);
        Set<String> stopsValidos = extraerStopsValidos(archivos.get("stop_times.txt"), tripsValidos);
        int paradasImportadas = procesarStops(archivos.get("stops.txt"), prefijo, nucleo, stopsValidos);
        procesarLineaEstacion(archivos.get("trips.txt"), archivos.get("stop_times.txt"), rutasValidas, prefijo);

        log.info("GTFS estático [{} - {}] importado: {} paradas, {} líneas",
                nucleo, prefijo, paradasImportadas, rutasValidas.size());

        return Map.of(
                "paradasImportadas", paradasImportadas,
                "lineasImportadas", rutasValidas.size()
        );
    }

    @Transactional
    public Map<String, Integer> importarHorarios(
            InputStream zipStream,
            String prefijo,
            String filtroRouteIdPrefix) throws Exception {

        Map<String, byte[]> archivos = leerZip(zipStream, Set.of(
                "routes.txt", "calendar.txt", "calendar_dates.txt", "trips.txt", "stop_times.txt", "frequencies.txt"
        ));

        Set<String> rutasValidas = extraerRouteIdsValidos(archivos.get("routes.txt"), filtroRouteIdPrefix);
        Map<String, String> tripToRoute = new HashMap<>();
        Set<String> servicesUsados = new HashSet<>();

        int tripsImportados = procesarTrips(
                archivos.get("trips.txt"), prefijo, rutasValidas, tripToRoute, servicesUsados
        );
        int calendariosImportados = procesarCalendar(archivos.get("calendar.txt"), prefijo, servicesUsados);
        int excepcionesImportadas = procesarCalendarDates(archivos.get("calendar_dates.txt"), prefijo, servicesUsados);
        int stopTimesImportados = procesarStopTimes(archivos.get("stop_times.txt"), prefijo, tripToRoute.keySet());
        int frecuenciasImportadas = procesarFrequencies(archivos.get("frequencies.txt"), prefijo, tripToRoute.keySet());

        log.info("GTFS horarios [{}] importados: {} trips, {} stop_times, {} frecuencias, {} excepciones",
                prefijo, tripsImportados, stopTimesImportados, frecuenciasImportadas, excepcionesImportadas);

        return Map.of(
                "tripsImportados", tripsImportados,
                "calendariosImportados", calendariosImportados,
                "excepcionesImportadas", excepcionesImportadas,
                "stopTimesImportados", stopTimesImportados,
                "frecuenciasImportadas", frecuenciasImportadas
        );
    }

    private Map<String, byte[]> leerZip(InputStream zipStream, Set<String> buscados) throws Exception {
        Map<String, byte[]> archivos = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(zipStream, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String nombre = entry.getName().toLowerCase();
                String clave = nombre.substring(nombre.lastIndexOf('/') + 1);
                if (buscados.contains(clave)) {
                    archivos.put(clave, zis.readAllBytes());
                }
                zis.closeEntry();
            }
        }
        return archivos;
    }

    private Set<String> extraerRouteIdsValidos(byte[] data, String filtroPrefix) throws Exception {
        Set<String> rutas = new HashSet<>();
        if (data == null) return rutas;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8))) {
            String h = r.readLine();
            if (h == null) return rutas;
            int idxId = mapearCabecera(h).getOrDefault("route_id", -1);
            if (idxId < 0) return rutas;
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> f = parsearLineaCsv(line);
                if (idxId >= f.size()) continue;
                String rawId = f.get(idxId).trim();
                if (rawId.isEmpty()) continue;
                if (filtroPrefix == null || filtroPrefix.isBlank() || rawId.startsWith(filtroPrefix)) {
                    rutas.add(rawId);
                }
            }
        }
        return rutas;
    }

    private int procesarTrips(
            byte[] data,
            String prefijo,
            Set<String> rutasValidas,
            Map<String, String> tripToRouteOut,
            Set<String> servicesUsadosOut) throws Exception {

        if (data == null || rutasValidas.isEmpty()) return 0;

        String sql = """
            INSERT INTO gtfs_trip (trip_id, id_linea, service_id, headsign, direction_id)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (trip_id) DO UPDATE SET
                id_linea = EXCLUDED.id_linea,
                service_id = EXCLUDED.service_id,
                headsign = EXCLUDED.headsign,
                direction_id = EXCLUDED.direction_id
            """;

        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
        int total = 0;

        try (BufferedReader r = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8))) {
            String h = r.readLine();
            if (h == null) return 0;
            Map<String, Integer> cols = mapearCabecera(h);
            int idxRoute = cols.getOrDefault("route_id", -1);
            int idxService = cols.getOrDefault("service_id", -1);
            int idxTrip = cols.getOrDefault("trip_id", -1);
            int idxHeadsign = cols.getOrDefault("trip_headsign", -1);
            int idxDir = cols.getOrDefault("direction_id", -1);
            if (idxRoute < 0 || idxService < 0 || idxTrip < 0) return 0;

            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> f = parsearLineaCsv(line);
                if (f.size() <= Math.max(idxRoute, Math.max(idxService, idxTrip))) continue;

                String rawRoute = f.get(idxRoute).trim();
                if (!rutasValidas.contains(rawRoute)) continue;

                String rawTrip = f.get(idxTrip).trim();
                String rawService = f.get(idxService).trim();
                if (rawTrip.isEmpty() || rawService.isEmpty()) continue;

                tripToRouteOut.put(rawTrip, rawRoute);
                servicesUsadosOut.add(rawService);

                String tripId = limitar(prefijo + rawTrip, 80);
                String idLinea = limitar(prefijo + rawRoute, 32);
                String serviceId = limitar(prefijo + rawService, 64);
                String headsign = (idxHeadsign >= 0 && idxHeadsign < f.size() && !f.get(idxHeadsign).isBlank())
                        ? limitar(f.get(idxHeadsign).trim(), 120)
                        : null;
                short dir = 0;
                if (idxDir >= 0 && idxDir < f.size() && !f.get(idxDir).isBlank()) {
                    try {
                        dir = Short.parseShort(f.get(idxDir).trim());
                    } catch (NumberFormatException ignored) {}
                }

                batch.add(new Object[]{tripId, idLinea, serviceId, headsign, dir});
                total++;
                if (batch.size() >= BATCH_SIZE) {
                    jdbcTemplate.batchUpdate(sql, batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            jdbcTemplate.batchUpdate(sql, batch);
        }
        return total;
    }

    private int procesarCalendar(byte[] data, String prefijo, Set<String> servicesUsados) throws Exception {
        if (data == null || servicesUsados.isEmpty()) return 0;

        String sql = """
            INSERT INTO gtfs_service_calendar
                (service_id, lunes, martes, miercoles, jueves, viernes, sabado, domingo, start_date, end_date)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (service_id) DO UPDATE SET
                lunes = EXCLUDED.lunes, martes = EXCLUDED.martes, miercoles = EXCLUDED.miercoles,
                jueves = EXCLUDED.jueves, viernes = EXCLUDED.viernes, sabado = EXCLUDED.sabado,
                domingo = EXCLUDED.domingo, start_date = EXCLUDED.start_date, end_date = EXCLUDED.end_date
            """;

        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
        int total = 0;

        try (BufferedReader r = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8))) {
            String h = r.readLine();
            if (h == null) return 0;
            Map<String, Integer> cols = mapearCabecera(h);
            int idxSvc = cols.getOrDefault("service_id", -1);
            int idxMon = cols.getOrDefault("monday", -1);
            int idxTue = cols.getOrDefault("tuesday", -1);
            int idxWed = cols.getOrDefault("wednesday", -1);
            int idxThu = cols.getOrDefault("thursday", -1);
            int idxFri = cols.getOrDefault("friday", -1);
            int idxSat = cols.getOrDefault("saturday", -1);
            int idxSun = cols.getOrDefault("sunday", -1);
            int idxStart = cols.getOrDefault("start_date", -1);
            int idxEnd = cols.getOrDefault("end_date", -1);
            if (idxSvc < 0 || idxStart < 0 || idxEnd < 0) return 0;

            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> f = parsearLineaCsv(line);
                if (f.size() <= Math.max(idxSvc, Math.max(idxStart, idxEnd))) continue;

                String rawSvc = f.get(idxSvc).trim();
                if (!servicesUsados.contains(rawSvc)) continue;

                LocalDate start = LocalDate.parse(f.get(idxStart).trim(), GTFS_DATE);
                LocalDate end = LocalDate.parse(f.get(idxEnd).trim(), GTFS_DATE);

                batch.add(new Object[]{
                        limitar(prefijo + rawSvc, 64),
                        esUno(f, idxMon), esUno(f, idxTue), esUno(f, idxWed),
                        esUno(f, idxThu), esUno(f, idxFri), esUno(f, idxSat), esUno(f, idxSun),
                        Date.valueOf(start), Date.valueOf(end)
                });
                total++;
                if (batch.size() >= BATCH_SIZE) {
                    jdbcTemplate.batchUpdate(sql, batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            jdbcTemplate.batchUpdate(sql, batch);
        }
        return total;
    }

    private int procesarCalendarDates(byte[] data, String prefijo, Set<String> servicesUsados) throws Exception {
        if (data == null || servicesUsados.isEmpty()) return 0;

        String sql = """
            INSERT INTO gtfs_service_exception (service_id, fecha, exception_type)
            VALUES (?, ?, ?)
            ON CONFLICT (service_id, fecha) DO UPDATE SET
                exception_type = EXCLUDED.exception_type
            """;

        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
        int total = 0;

        try (BufferedReader r = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8))) {
            String h = r.readLine();
            if (h == null) return 0;
            Map<String, Integer> cols = mapearCabecera(h);
            int idxSvc = cols.getOrDefault("service_id", -1);
            int idxDate = cols.getOrDefault("date", -1);
            int idxType = cols.getOrDefault("exception_type", -1);
            if (idxSvc < 0 || idxDate < 0 || idxType < 0) return 0;

            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> f = parsearLineaCsv(line);
                if (f.size() <= Math.max(idxSvc, Math.max(idxDate, idxType))) continue;

                String rawSvc = f.get(idxSvc).trim();
                if (!servicesUsados.contains(rawSvc)) continue;

                LocalDate fecha = LocalDate.parse(f.get(idxDate).trim(), GTFS_DATE);
                short exType = Short.parseShort(f.get(idxType).trim());

                batch.add(new Object[]{limitar(prefijo + rawSvc, 64), Date.valueOf(fecha), exType});
                total++;
                if (batch.size() >= BATCH_SIZE) {
                    jdbcTemplate.batchUpdate(sql, batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            jdbcTemplate.batchUpdate(sql, batch);
        }
        return total;
    }

    private int procesarStopTimes(byte[] data, String prefijo, Set<String> tripsValidos) throws Exception {
        if (data == null || tripsValidos.isEmpty()) return 0;

        String sql = """
            INSERT INTO gtfs_stop_time (trip_id, id_estacion, stop_sequence, arrival_seconds, departure_seconds)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (trip_id, stop_sequence) DO UPDATE SET
                id_estacion = EXCLUDED.id_estacion,
                arrival_seconds = EXCLUDED.arrival_seconds,
                departure_seconds = EXCLUDED.departure_seconds
            """;

        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
        int total = 0;

        try (BufferedReader r = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8))) {
            String h = r.readLine();
            if (h == null) return 0;
            Map<String, Integer> cols = mapearCabecera(h);
            int idxTrip = cols.getOrDefault("trip_id", -1);
            int idxArr = cols.getOrDefault("arrival_time", -1);
            int idxDep = cols.getOrDefault("departure_time", -1);
            int idxStop = cols.getOrDefault("stop_id", -1);
            int idxSeq = cols.getOrDefault("stop_sequence", -1);
            if (idxTrip < 0 || idxStop < 0 || idxSeq < 0) return 0;

            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> f = parsearLineaCsv(line);
                if (f.size() <= Math.max(idxTrip, Math.max(idxStop, idxSeq))) continue;

                String rawTrip = f.get(idxTrip).trim();
                if (!tripsValidos.contains(rawTrip)) continue;

                int arrSec = parsearHoraSegundos(idxArr >= 0 && idxArr < f.size() ? f.get(idxArr) : null);
                int depSec = parsearHoraSegundos(idxDep >= 0 && idxDep < f.size() ? f.get(idxDep) : null);
                if (arrSec < 0 && depSec >= 0) arrSec = depSec;
                if (depSec < 0 && arrSec >= 0) depSec = arrSec;
                if (depSec < 0) continue;

                int seq = Integer.parseInt(f.get(idxSeq).trim());
                String tripId = limitar(prefijo + rawTrip, 80);
                String idEstacion = limitar(prefijo + f.get(idxStop).trim(), 32);

                batch.add(new Object[]{tripId, idEstacion, seq, arrSec, depSec});
                total++;
                if (batch.size() >= BATCH_SIZE) {
                    jdbcTemplate.batchUpdate(sql, batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            jdbcTemplate.batchUpdate(sql, batch);
        }
        return total;
    }

    private int procesarFrequencies(byte[] data, String prefijo, Set<String> tripsValidos) throws Exception {
        if (data == null || tripsValidos.isEmpty()) return 0;

        // Limpiar frecuencias previas de esos trips para que sea idempotente
        jdbcTemplate.update("DELETE FROM gtfs_frequency WHERE trip_id LIKE ?", prefijo + "%");

        String sql = """
            INSERT INTO gtfs_frequency (trip_id, start_seconds, end_seconds, headway_secs)
            VALUES (?, ?, ?, ?)
            """;

        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
        int total = 0;

        try (BufferedReader r = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8))) {
            String h = r.readLine();
            if (h == null) return 0;
            Map<String, Integer> cols = mapearCabecera(h);
            int idxTrip = cols.getOrDefault("trip_id", -1);
            int idxStart = cols.getOrDefault("start_time", -1);
            int idxEnd = cols.getOrDefault("end_time", -1);
            int idxHeadway = cols.getOrDefault("headway_secs", -1);
            if (idxTrip < 0 || idxStart < 0 || idxEnd < 0 || idxHeadway < 0) return 0;

            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> f = parsearLineaCsv(line);
                if (f.size() <= Math.max(idxTrip, Math.max(idxEnd, idxHeadway))) continue;

                String rawTrip = f.get(idxTrip).trim();
                if (!tripsValidos.contains(rawTrip)) continue;

                int startSec = parsearHoraSegundos(f.get(idxStart));
                int endSec = parsearHoraSegundos(f.get(idxEnd));
                int headway = Integer.parseInt(f.get(idxHeadway).trim());
                if (startSec < 0 || endSec < 0 || headway <= 0) continue;

                batch.add(new Object[]{limitar(prefijo + rawTrip, 80), startSec, endSec, headway});
                total++;
                if (batch.size() >= BATCH_SIZE) {
                    jdbcTemplate.batchUpdate(sql, batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            jdbcTemplate.batchUpdate(sql, batch);
        }
        return total;
    }

    private int parsearHoraSegundos(String raw) {
        if (raw == null || raw.isBlank()) return -1;
        String[] parts = raw.trim().split(":");
        if (parts.length != 3) return -1;
        try {
            int h = Integer.parseInt(parts[0].trim());
            int m = Integer.parseInt(parts[1].trim());
            int s = Integer.parseInt(parts[2].trim());
            return h * 3600 + m * 60 + s;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private boolean esUno(List<String> f, int idx) {
        return idx >= 0 && idx < f.size() && "1".equals(f.get(idx).trim());
    }

    private Set<String> procesarRoutes(byte[] data, String prefijo, String operador, String filtroPrefix) throws Exception {
        Set<String> rutasValidas = new HashSet<>();
        if (data == null) return rutasValidas;

        BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8));
        String headerLine = reader.readLine();
        if (headerLine == null) return rutasValidas;

        Map<String, Integer> cols = mapearCabecera(headerLine);
        int idxId = cols.getOrDefault("route_id", -1);
        int idxShort = cols.getOrDefault("route_short_name", -1);
        int idxLong = cols.getOrDefault("route_long_name", -1);
        int idxColor = cols.getOrDefault("route_color", -1);

        String sql = """
            INSERT INTO linea (id_linea, codigo_corto, nombre_largo, operador, color_hex)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (id_linea) DO UPDATE SET
                codigo_corto = EXCLUDED.codigo_corto,
                nombre_largo = EXCLUDED.nombre_largo,
                operador = EXCLUDED.operador,
                color_hex = EXCLUDED.color_hex
            """;

        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
        String line;

        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) continue;
            List<String> f = parsearLineaCsv(line);
            if (idxId < 0 || idxId >= f.size()) continue;

            String rawId = f.get(idxId).trim();
            if (rawId.isEmpty()) continue;

            if (filtroPrefix != null && !filtroPrefix.isBlank() && !rawId.startsWith(filtroPrefix)) {
                continue;
            }

            rutasValidas.add(rawId);

            String idLinea = limitar(prefijo + rawId, 32);
            String shortName = (idxShort >= 0 && idxShort < f.size() && !f.get(idxShort).isBlank())
                    ? limitar(f.get(idxShort).trim(), 20)
                    : limitar(rawId, 20);
            String longName = (idxLong >= 0 && idxLong < f.size() && !f.get(idxLong).isBlank())
                    ? limitar(f.get(idxLong).trim(), 150)
                    : shortName;

            String colorHex = "#E60000";
            if (idxColor >= 0 && idxColor < f.size()) {
                String rawColor = f.get(idxColor).trim().replace("#", "");
                if (rawColor.matches("^[0-9A-Fa-f]{6}$")) {
                    colorHex = "#" + rawColor.toUpperCase();
                }
            }

            batch.add(new Object[]{idLinea, shortName, longName, operador, colorHex});
            if (batch.size() >= BATCH_SIZE) {
                jdbcTemplate.batchUpdate(sql, batch);
                batch.clear();
            }
        }

        if (!batch.isEmpty()) {
            jdbcTemplate.batchUpdate(sql, batch);
        }
        return rutasValidas;
    }

    private Set<String> extraerTripsValidos(byte[] data, Set<String> rutasValidas) throws Exception {
        Set<String> tripsValidos = new HashSet<>();
        if (data == null || rutasValidas.isEmpty()) return tripsValidos;

        BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8));
        String headerLine = reader.readLine();
        if (headerLine == null) return tripsValidos;

        Map<String, Integer> cols = mapearCabecera(headerLine);
        int idxRoute = cols.getOrDefault("route_id", -1);
        int idxTrip = cols.getOrDefault("trip_id", -1);
        if (idxRoute < 0 || idxTrip < 0) return tripsValidos;

        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) continue;
            List<String> f = parsearLineaCsv(line);
            if (f.size() <= Math.max(idxRoute, idxTrip)) continue;
            if (rutasValidas.contains(f.get(idxRoute).trim())) {
                tripsValidos.add(f.get(idxTrip).trim());
            }
        }
        return tripsValidos;
    }

    private Set<String> extraerStopsValidos(byte[] data, Set<String> tripsValidos) throws Exception {
        Set<String> stopsValidos = new HashSet<>();
        if (data == null || tripsValidos.isEmpty()) return stopsValidos;

        BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8));
        String headerLine = reader.readLine();
        if (headerLine == null) return stopsValidos;

        Map<String, Integer> cols = mapearCabecera(headerLine);
        int idxTrip = cols.getOrDefault("trip_id", -1);
        int idxStop = cols.getOrDefault("stop_id", -1);
        if (idxTrip < 0 || idxStop < 0) return stopsValidos;

        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) continue;
            List<String> f = parsearLineaCsv(line);
            if (f.size() <= Math.max(idxTrip, idxStop)) continue;
            if (tripsValidos.contains(f.get(idxTrip).trim())) {
                stopsValidos.add(f.get(idxStop).trim());
            }
        }
        return stopsValidos;
    }

    private int procesarStops(byte[] data, String prefijo, String nucleo, Set<String> stopsValidos) throws Exception {
        if (data == null) return 0;

        BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8));
        String headerLine = reader.readLine();
        if (headerLine == null) return 0;

        Map<String, Integer> cols = mapearCabecera(headerLine);
        int idxId = cols.getOrDefault("stop_id", -1);
        int idxCode = cols.getOrDefault("stop_code", -1);
        int idxName = cols.getOrDefault("stop_name", -1);
        int idxLat = cols.getOrDefault("stop_lat", -1);
        int idxLon = cols.getOrDefault("stop_lon", -1);
        int idxWheelchair = cols.getOrDefault("wheelchair_boarding", -1);

        String sql = """
            INSERT INTO estacion (id_estacion, codigo_parada, nombre, latitud, longitud, nucleo, accesible_pmr)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id_estacion) DO UPDATE SET
                codigo_parada = EXCLUDED.codigo_parada,
                nombre = EXCLUDED.nombre,
                latitud = EXCLUDED.latitud,
                longitud = EXCLUDED.longitud,
                nucleo = EXCLUDED.nucleo,
                accesible_pmr = EXCLUDED.accesible_pmr
            """;

        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
        int total = 0;
        String line;

        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) continue;
            List<String> f = parsearLineaCsv(line);
            if (idxId < 0 || idxName < 0 || idxLat < 0 || idxLon < 0 || f.size() <= Math.max(idxLat, idxLon)) {
                continue;
            }

            String rawId = f.get(idxId).trim();
            if (rawId.isEmpty()) continue;

            if (!stopsValidos.isEmpty() && !stopsValidos.contains(rawId)) {
                continue;
            }

            String idEstacion = limitar(prefijo + rawId, 32);
            String codigoParada = (idxCode >= 0 && idxCode < f.size() && !f.get(idxCode).isBlank())
                    ? limitar(f.get(idxCode).trim(), 20)
                    : limitar(rawId, 20);
            String nombre = limitar(f.get(idxName).trim(), 120);

            BigDecimal lat;
            BigDecimal lon;
            try {
                lat = new BigDecimal(f.get(idxLat).trim());
                lon = new BigDecimal(f.get(idxLon).trim());
            } catch (NumberFormatException e) {
                continue;
            }

            boolean pmr = true;
            if (idxWheelchair >= 0 && idxWheelchair < f.size()) {
                pmr = !"2".equals(f.get(idxWheelchair).trim());
            }

            batch.add(new Object[]{idEstacion, codigoParada, nombre, lat, lon, nucleo, pmr});
            total++;

            if (batch.size() >= BATCH_SIZE) {
                jdbcTemplate.batchUpdate(sql, batch);
                batch.clear();
            }
        }

        if (!batch.isEmpty()) {
            jdbcTemplate.batchUpdate(sql, batch);
        }
        return total;
    }

    private void procesarLineaEstacion(byte[] tripsData, byte[] stopTimesData, Set<String> rutasValidas, String prefijo) throws Exception {
        if (tripsData == null || stopTimesData == null || rutasValidas.isEmpty()) return;

        Map<String, String> tripToRoute = new HashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(tripsData), StandardCharsets.UTF_8))) {
            String h = r.readLine();
            if (h == null) return;
            Map<String, Integer> cols = mapearCabecera(h);
            int idxRoute = cols.getOrDefault("route_id", -1);
            int idxTrip = cols.getOrDefault("trip_id", -1);
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> f = parsearLineaCsv(line);
                if (f.size() <= Math.max(idxRoute, idxTrip)) continue;
                String routeId = f.get(idxRoute).trim();
                if (rutasValidas.contains(routeId)) {
                    tripToRoute.put(f.get(idxTrip).trim(), routeId);
                }
            }
        }

        Set<String> paresUnicos = new HashSet<>();
        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
        String sql = """
            INSERT INTO linea_estacion (id_linea, id_estacion, orden_parada)
            VALUES (?, ?, ?)
            ON CONFLICT DO NOTHING
            """;

        try (BufferedReader r = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(stopTimesData), StandardCharsets.UTF_8))) {
            String h = r.readLine();
            if (h == null) return;
            Map<String, Integer> cols = mapearCabecera(h);
            int idxTrip = cols.getOrDefault("trip_id", -1);
            int idxStop = cols.getOrDefault("stop_id", -1);
            int idxSeq = cols.getOrDefault("stop_sequence", -1);
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> f = parsearLineaCsv(line);
                if (f.size() <= Math.max(idxTrip, idxStop)) continue;
                String routeId = tripToRoute.get(f.get(idxTrip).trim());
                if (routeId == null) continue;

                String idLinea = limitar(prefijo + routeId, 32);
                String idEstacion = limitar(prefijo + f.get(idxStop).trim(), 32);
                String clave = idLinea + "|" + idEstacion;

                int orden = 0;
                if (idxSeq >= 0 && idxSeq < f.size()) {
                    try {
                        orden = Integer.parseInt(f.get(idxSeq).trim());
                    } catch (NumberFormatException ignored) {}
                }

                if (paresUnicos.add(clave)) {
                    batch.add(new Object[]{idLinea, idEstacion, orden});
                    if (batch.size() >= BATCH_SIZE) {
                        jdbcTemplate.batchUpdate(sql, batch);
                        batch.clear();
                    }
                }
            }
        }
        if (!batch.isEmpty()) {
            jdbcTemplate.batchUpdate(sql, batch);
        }
    }

    private Map<String, Integer> mapearCabecera(String headerLine) {
        String limpia = headerLine.replace("\uFEFF", "");
        List<String> headers = parsearLineaCsv(limpia);
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            map.put(headers.get(i).trim().toLowerCase(), i);
        }
        return map;
    }

    private List<String> parsearLineaCsv(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean enComillas = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                enComillas = !enComillas;
            } else if (c == ',' && !enComillas) {
                result.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        result.add(sb.toString());
        return result;
    }

    private String limitar(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}