package com.bypass.backend.service;

import com.bypass.backend.entity.*;
import com.bypass.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Service
@RequiredArgsConstructor
public class GtfsImportService {

    private static final Logger log = LoggerFactory.getLogger(GtfsImportService.class);

    private final LineaRepository lineaRepo;
    private final EstacionRepository estacionRepo;
    private final LineaEstacionRepository lineaEstacionRepo;

    @Transactional
    public Map<String, Integer> importarNucleoMadridDesdeDirectorio(Path directorioGtfs) throws IOException {
        Path routesFile = directorioGtfs.resolve("routes.txt").normalize();
        Path stopsFile = directorioGtfs.resolve("stops.txt").normalize();
        Path tripsFile = directorioGtfs.resolve("trips.txt").normalize();
        Path stopTimesFile = directorioGtfs.resolve("stop_times.txt").normalize();

        // Validación anti Path-Traversal
        if (!routesFile.startsWith(directorioGtfs) || !stopsFile.startsWith(directorioGtfs)) {
            throw new SecurityException("Ruta de archivos GTFS no válida.");
        }

        // 1. Importar líneas del núcleo de Madrid (prefijo 10T en Renfe GTFS)
        Map<String, Linea> lineasMadrid = new HashMap<>();
        try (BufferedReader br = Files.newBufferedReader(routesFile, StandardCharsets.UTF_8)) {
            String header = br.readLine();
            String line;
            while ((line = br.readLine()) != null) {
                String[] cols = splitCsv(line);
                if (cols.length < 4) continue;
                String routeId = limpiar(cols[0]);
                if (!routeId.startsWith("10T")) continue;

                String shortName = limpiar(cols[2]);
                String longName = limpiar(cols[3]);
                String color = (cols.length > 7 && !limpiar(cols[7]).isBlank())
                        ? "#" + limpiar(cols[7]) : "#E60000";

                Linea linea = Linea.builder()
                        .idLinea("MAD_" + shortName.replace("-", ""))
                        .codigoCorto(shortName)
                        .nombreLargo(longName)
                        .operador("RENFE_CERCANIAS")
                        .colorHex(color.length() == 7 ? color : "#E60000")
                        .build();
                lineasMadrid.put(routeId, lineaRepo.save(linea));
            }
        }

        // 2. Mapear un trip representativo por cada línea de Madrid
        Map<String, String> tripToRoute = new HashMap<>();
        Set<String> tripsSeleccionados = new HashSet<>();
        try (BufferedReader br = Files.newBufferedReader(tripsFile, StandardCharsets.UTF_8)) {
            br.readLine();
            String line;
            while ((line = br.readLine()) != null) {
                String[] cols = splitCsv(line);
                if (cols.length < 3) continue;
                String routeId = limpiar(cols[0]);
                String tripId = limpiar(cols[2]);
                if (lineasMadrid.containsKey(routeId) && !tripToRoute.containsValue(routeId)) {
                    tripToRoute.put(tripId, routeId);
                    tripsSeleccionados.add(tripId);
                }
            }
        }

        // 3. Leer paradas usadas por Madrid en stop_times.txt
        Map<String, List<String[]>> paradasPorTrip = new HashMap<>();
        Set<String> stopIdsMadrid = new HashSet<>();
        try (BufferedReader br = Files.newBufferedReader(stopTimesFile, StandardCharsets.UTF_8)) {
            br.readLine();
            String line;
            while ((line = br.readLine()) != null) {
                String[] cols = splitCsv(line);
                if (cols.length < 5) continue;
                String tripId = limpiar(cols[0]);
                if (tripsSeleccionados.contains(tripId)) {
                    String stopId = limpiar(cols[3]);
                    paradasPorTrip.computeIfAbsent(tripId, k -> new ArrayList<>()).add(cols);
                    stopIdsMadrid.add(stopId);
                }
            }
        }

        // 4. Importar solo estaciones de Madrid
        Map<String, Estacion> estacionesGuardadas = new HashMap<>();
        try (BufferedReader br = Files.newBufferedReader(stopsFile, StandardCharsets.UTF_8)) {
            br.readLine();
            String line;
            while ((line = br.readLine()) != null) {
                String[] cols = splitCsv(line);
                if (cols.length < 6) continue;
                String stopId = limpiar(cols[0]);
                if (!stopIdsMadrid.contains(stopId)) continue;

                String nombre = limpiar(cols[2]);
                BigDecimal lat = new BigDecimal(limpiar(cols[4]));
                BigDecimal lon = new BigDecimal(limpiar(cols[5]));

                Estacion est = Estacion.builder()
                        .idEstacion("EST_" + stopId)
                        .nombre(nombre)
                        .latitud(lat)
                        .longitud(lon)
                        .nucleo("MADRID")
                        .tieneEnlaceMetro(esIntercambiadorMetro(nombre))
                        .tieneEnlaceBus(true)
                        .build();
                estacionesGuardadas.put(stopId, estacionRepo.save(est));
            }
        }

        // 5. Guardar orden de paradas por línea
        int relaciones = 0;
        for (var entry : paradasPorTrip.entrySet()) {
            Linea linea = lineasMadrid.get(tripToRoute.get(entry.getKey()));
            List<String[]> filas = entry.getValue();
            filas.sort(Comparator.comparingInt(c -> Integer.parseInt(limpiar(c[4]))));

            int orden = 1;
            for (String[] fila : filas) {
                Estacion est = estacionesGuardadas.get(limpiar(fila[3]));
                if (est == null) continue;

                LineaEstacionId id = new LineaEstacionId(linea.getIdLinea(), est.getIdEstacion());
                LineaEstacion le = LineaEstacion.builder()
                        .id(id)
                        .linea(linea)
                        .estacion(est)
                        .ordenParada(orden++)
                        .tiempoSiguienteMin(3)
                        .build();
                lineaEstacionRepo.save(le);
                relaciones++;
            }
        }

        log.info("Importación GTFS Madrid completada: {} líneas, {} estaciones, {} paradas",
                lineasMadrid.size(), estacionesGuardadas.size(), relaciones);

        return Map.of(
                "lineas", lineasMadrid.size(),
                "estaciones", estacionesGuardadas.size(),
                "paradas", relaciones
        );
    }

    private String[] splitCsv(String line) {
        return line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1);
    }

    private String limpiar(String val) {
        return val.replace("\"", "").trim();
    }

    private boolean esIntercambiadorMetro(String nombre) {
        String n = nombre.toLowerCase();
        return n.contains("atocha") || n.contains("chamart") || n.contains("sol")
                || n.contains("nuevos ministerios") || n.contains("príncipe pío")
                || n.contains("embajadores") || n.contains("aluche") || n.contains("laguna")
                || n.contains("villaverde alto") || n.contains("méndez álvaro")
                || n.contains("delicias") || n.contains("pirámides") || n.contains("getafe")
                || n.contains("leganés") || n.contains("móstoles") || n.contains("fuenlabrada")
                || n.contains("coslada") || n.contains("vicálvaro") || n.contains("vallecas")
                || n.contains("sierra de guadalupe") || n.contains("paco de lucía")
                || n.contains("pitis") || n.contains("aeropuerto");
    }
}