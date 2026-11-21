package com.bypass.backend.service;

import com.bypass.backend.entity.Linea;
import com.bypass.backend.entity.OcupacionVagon;
import com.bypass.backend.entity.Tren;
import com.bypass.backend.repository.LineaRepository;
import com.bypass.backend.repository.OcupacionVagonRepository;
import com.bypass.backend.repository.TrenRepository;
import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtime.TripUpdate;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GtfsRtSyncService {

    private static final Logger log = LoggerFactory.getLogger(GtfsRtSyncService.class);

    private final LineaRepository lineaRepo;
    private final TrenRepository trenRepo;
    private final OcupacionVagonRepository vagonRepo;

    @Value("${app.gtfsrt.enabled:true}")
    private boolean enabled;

    @Value("${app.gtfsrt.trip-updates-url}")
    private String tripUpdatesUrl;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER) // Anti-SSRF por redirecciones abiertas
            .build();

    @Scheduled(fixedDelay = 30000, initialDelay = 5000)
    @Transactional
    public void sincronizarTrenesMadrid() {
        if (!enabled) return;

        try {
            URI uri = URI.create(tripUpdatesUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                log.warn("URL GTFS-RT rechazada por no usar HTTPS");
                return;
            }

            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "ByPass-Mobility-Backend/1.0")
                    .GET()
                    .build();

            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                log.warn("Feed GTFS-RT devolvió código HTTP {}", response.statusCode());
                return;
            }

            FeedMessage feed;
            try (InputStream is = response.body()) {
                feed = FeedMessage.parseFrom(is);
            }

            Map<String, Linea> lineasPorId = lineaRepo.findAll().stream()
                    .collect(Collectors.toMap(Linea::getIdLinea, l -> l));

            int actualizados = 0;
            for (FeedEntity entity : feed.getEntityList()) {
                if (!entity.hasTripUpdate()) continue;

                TripUpdate tu = entity.getTripUpdate();
                String routeId = tu.getTrip().getRouteId();
                // Filtrar solo núcleo Madrid (prefijo 10T)
                if (routeId == null || !routeId.startsWith("10T")) continue;

                Linea linea = resolverLineaMadrid(routeId, lineasPorId);
                if (linea == null) continue;

                String idTren = sanitizarId(tu.getTrip().getTripId());
                int retrasoSeg = tu.hasDelay() ? tu.getDelay() : extraerMaxDelayParadas(tu);
                int retrasoMin = Math.max(0, Math.round(retrasoSeg / 60.0f));

                String estado = retrasoMin >= 15 ? "DETENIDO"
                        : (retrasoMin >= 4 ? "RETRASADO" : "EN_HORA");

                Tren tren = trenRepo.findById(idTren).orElseGet(() -> Tren.builder()
                        .idTren(idTren)
                        .linea(linea)
                        .destino(linea.getNombreLargo())
                        .numVagones(6)
                        .build());

                tren.setLinea(linea);
                tren.setEstado(estado);
                tren.setMinutosRetraso(retrasoMin);
                tren.setUltimaActualizacion(OffsetDateTime.now());
                Tren guardado = trenRepo.save(tren);

                inicializarVagonesSiNoExisten(guardado);
                actualizados++;
            }

            if (actualizados > 0) {
                log.debug("GTFS-RT Madrid sincronizado: {} trenes activos actualizados", actualizados);
            }
        } catch (Exception ex) {
            log.warn("No se pudo sincronizar GTFS-RT en este ciclo: {}", ex.getMessage());
        }
    }

    private Linea resolverLineaMadrid(String routeId, Map<String, Linea> lineasPorId) {
        for (Linea l : lineasPorId.values()) {
            String codigoLimpio = l.getCodigoCorto().replace("-", "").toUpperCase();
            if (routeId.toUpperCase().contains(codigoLimpio)) {
                return l;
            }
        }
        return null;
    }

    private int extraerMaxDelayParadas(TripUpdate tu) {
        return tu.getStopTimeUpdateList().stream()
                .mapToInt(stu -> stu.hasArrival() ? stu.getArrival().getDelay()
                        : (stu.hasDeparture() ? stu.getDeparture().getDelay() : 0))
                .max()
                .orElse(0);
    }

    private void inicializarVagonesSiNoExisten(Tren tren) {
        if (!vagonRepo.findByTrenIdTrenOrderByNumeroVagonAsc(tren.getIdTren()).isEmpty()) {
            return;
        }
        for (int i = 1; i <= tren.getNumVagones(); i++) {
            vagonRepo.save(OcupacionVagon.builder()
                    .tren(tren)
                    .numeroVagon(i)
                    .nivelSaturacion("MODERADO")
                    .scorePonderado(new BigDecimal("45.00"))
                    .build());
        }
    }

    private String sanitizarId(String raw) {
        String limpio = raw.replaceAll("[^A-Za-z0-9_-]", "_");
        return limpio.length() > 50 ? limpio.substring(0, 50) : limpio;
    }
}