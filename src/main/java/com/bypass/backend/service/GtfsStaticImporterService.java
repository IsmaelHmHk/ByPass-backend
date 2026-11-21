package com.bypass.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class GtfsStaticImporterService {

    private final JdbcTemplate jdbcTemplate;
    private static final int BATCH_SIZE = 500;

    /**
     * Importa stops.txt y routes.txt desde un ZIP GTFS oficial.
     *
     * @param zipStream Flujo del archivo .zip GTFS
     * @param prefijo   Prefijo del operador: "renfe_", "metro_", "emt_", "crtm_"
     * @param operador  Nombre del operador: "RENFE_CERCANIAS", "METRO_MADRID", "EMT_MADRID"
     */
    @Transactional
    public Map<String, Integer> importarStopsYRoutes(InputStream zipStream, String prefijo, String operador) throws Exception {
        int paradasImportadas = 0;
        int lineasImportadas = 0;

        try (ZipInputStream zis = new ZipInputStream(zipStream, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String nombreArchivo = entry.getName().toLowerCase();
                if (nombreArchivo.endsWith("stops.txt")) {
                    paradasImportadas = procesarStops(zis, prefijo);
                } else if (nombreArchivo.endsWith("routes.txt")) {
                    lineasImportadas = procesarRoutes(zis, prefijo, operador);
                }
                zis.closeEntry();
            }
        }

        log.info("GTFS [{}] importado: {} paradas, {} líneas", prefijo, paradasImportadas, lineasImportadas);
        return Map.of(
                "paradasImportadas", paradasImportadas,
                "lineasImportadas", lineasImportadas
        );
    }

    private int procesarStops(InputStream in, String prefijo) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
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
            VALUES (?, ?, ?, ?, ?, 'MADRID', ?)
            ON CONFLICT (id_estacion) DO UPDATE SET
                codigo_parada = EXCLUDED.codigo_parada,
                nombre = EXCLUDED.nombre,
                latitud = EXCLUDED.latitud,
                longitud = EXCLUDED.longitud,
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

            // En GTFS: 1 = accesible, 2 = no accesible, 0 o vacío = sin info (asumimos true salvo '2')
            boolean pmr = true;
            if (idxWheelchair >= 0 && idxWheelchair < f.size()) {
                pmr = !"2".equals(f.get(idxWheelchair).trim());
            }

            batch.add(new Object[]{idEstacion, codigoParada, nombre, lat, lon, pmr});
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

    private int procesarRoutes(InputStream in, String prefijo, String operador) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String headerLine = reader.readLine();
        if (headerLine == null) return 0;

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
        int total = 0;
        String line;

        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) continue;
            List<String> f = parsearLineaCsv(line);
            if (idxId < 0 || idxId >= f.size()) continue;

            String rawId = f.get(idxId).trim();
            if (rawId.isEmpty()) continue;

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

    private Map<String, Integer> mapearCabecera(String headerLine) {
        // Eliminar BOM UTF-8 (\uFEFF) si viene en el fichero oficial
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