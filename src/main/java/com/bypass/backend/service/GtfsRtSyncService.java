package com.bypass.backend.service;

import com.bypass.backend.entity.*;
import com.bypass.backend.repository.*;
import com.google.transit.realtime.GtfsRealtime.*;
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
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GtfsRtSyncService {

    private static final Logger log = LoggerFactory.getLogger(GtfsRtSyncService.class);
    private static final String EMAIL_SISTEMA_RT = "sistema_gtfsrt@bypass.local";

    private final LineaRepository lineaRepo;
    private final EstacionRepository estacionRepo;
    private final TrenRepository trenRepo;
    private final OcupacionVagonRepository vagonRepo;
    private final ReporteIncidenciaRepository reporteRepo;
    private final UsuarioRepository usuarioRepo;

    @Value("${app.gtfsrt.enabled:true}")
    private boolean enabled;

    @Value("${app.gtfsrt.trip-updates-url}")
    private String tripUpdatesUrl;

    @Value("${app.gtfsrt.alerts-url:https://gtfsrt.renfe.com/alerts.pb}")
    private String alertsUrl;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Scheduled(fixedDelay = 30000, initialDelay = 5000)
    @Transactional
    public void sincronizarTrenesMadrid() {
        if (!enabled) return;

        try {
            FeedMessage feed = descargarFeedProtobuf(tripUpdatesUrl);
            if (feed == null) return;

            Map<String, Linea> lineasPorId = lineaRepo.findAll().stream()
                    .collect(Collectors.toMap(Linea::getIdLinea, l -> l));

            int actualizados = 0;
            for (FeedEntity entity : feed.getEntityList()) {
                if (!entity.hasTripUpdate()) continue;

                TripUpdate tu = entity.getTripUpdate();
                String tripIdRaw = tu.getTrip().getTripId();
                String routeId = tu.getTrip().getRouteId();

                // Filtrar núcleo Madrid por routeId o tripId (prefijo 10T / 10)
                boolean esMadrid = (routeId != null && routeId.startsWith("10"))
                        || (tripIdRaw != null && tripIdRaw.startsWith("10"));
                if (!esMadrid) continue;

                Linea linea = resolverLineaMadrid(routeId, tripIdRaw, lineasPorId);
                if (linea == null) continue;

                String idTren = sanitizarId(tripIdRaw);
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

            log.info("GTFS-RT TripUpdates Madrid: {} trenes activos sincronizados (total entidades feed: {})",
                    actualizados, feed.getEntityCount());
        } catch (Exception ex) {
            log.warn("No se pudo sincronizar TripUpdates GTFS-RT: {}", ex.getMessage());
        }
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 8000)
    @Transactional
    public void sincronizarAlertasMadrid() {
        if (!enabled) return;

        try {
            FeedMessage feed = descargarFeedProtobuf(alertsUrl);
            if (feed == null) return;

            Usuario sistema = usuarioRepo.findByEmail(EMAIL_SISTEMA_RT).orElse(null);
            if (sistema == null) {
                log.warn("Usuario {} no encontrado; omitiendo ingesta de alertas GTFS-RT", EMAIL_SISTEMA_RT);
                return;
            }

            Map<String, Linea> lineasPorId = lineaRepo.findAll().stream()
                    .collect(Collectors.toMap(Linea::getIdLinea, l -> l));

            List<ReporteIncidencia> vigentes = reporteRepo.findAll().stream()
                    .filter(r -> r.getUsuario().getIdUsuario().equals(sistema.getIdUsuario()))
                    .filter(r -> r.getExpiraEn().isAfter(OffsetDateTime.now()))
                    .toList();

            Set<String> etiquetasActivasEnFeed = new HashSet<>();
            int procesadas = 0;

            for (FeedEntity entity : feed.getEntityList()) {
                if (!entity.hasAlert()) continue;

                Alert alert = entity.getAlert();
                Linea lineaAfectada = null;
                Estacion estacionAfectada = null;
                boolean esMadrid = false;

                for (EntitySelector sel : alert.getInformedEntityList()) {
                    if (sel.hasRouteId() && sel.getRouteId().startsWith("10T")) {
                        esMadrid = true;
                        if (lineaAfectada == null) {
                            lineaAfectada = resolverLineaMadrid(sel.getRouteId(), null, lineasPorId);
                        }
                    }
                    if (sel.hasStopId()) {
                        String idEst = "madrid_renfe_" + sel.getStopId();
                        var estOpt = estacionRepo.findById(idEst);
                        if (estOpt.isPresent()) {
                            esMadrid = true;
                            if (estacionAfectada == null) {
                                estacionAfectada = estOpt.get();
                            }
                        }
                    }
                }

                if (!esMadrid) continue;

                String textoAlerta = extraerTextoAlerta(alert);
                if (textoAlerta.isBlank()) continue;

                String etiquetaId = "[GTFS-RT:" + sanitizarId(entity.getId()) + "]";
                etiquetasActivasEnFeed.add(etiquetaId);

                String tipoIncidencia = clasificarTipoAlerta(alert, textoAlerta);
                String descripcionFinal = limitarLongitud(etiquetaId + " " + textoAlerta, 495);

                Optional<ReporteIncidencia> existente = vigentes.stream()
                        .filter(r -> r.getDescripcion() != null && r.getDescripcion().startsWith(etiquetaId))
                        .findFirst();

                if (existente.isPresent()) {
                    ReporteIncidencia rep = existente.get();
                    rep.setDescripcion(descripcionFinal);
                    rep.setTipoIncidencia(tipoIncidencia);
                    rep.setExpiraEn(OffsetDateTime.now().plusMinutes(15));
                    reporteRepo.save(rep);
                } else {
                    reporteRepo.save(ReporteIncidencia.builder()
                            .usuario(sistema)
                            .linea(lineaAfectada)
                            .estacion(estacionAfectada)
                            .tipoIncidencia(tipoIncidencia)
                            .descripcion(descripcionFinal)
                            .pesoConfianza(new BigDecimal("5.00"))
                            .estadoVerificacion("CONFIRMADO")
                            .expiraEn(OffsetDateTime.now().plusMinutes(15))
                            .build());
                }
                procesadas++;
            }

            // Marcar como RESUELTO alertas oficiales que ya desaparecieron del feed de Renfe
            for (ReporteIncidencia rep : vigentes) {
                String desc = rep.getDescripcion();
                if (desc != null && desc.startsWith("[GTFS-RT:")) {
                    int finTag = desc.indexOf(']');
                    if (finTag > 0) {
                        String tag = desc.substring(0, finTag + 1);
                        if (!etiquetasActivasEnFeed.contains(tag)) {
                            rep.setEstadoVerificacion("RESUELTO");
                            rep.setExpiraEn(OffsetDateTime.now());
                            reporteRepo.save(rep);
                        }
                    }
                }
            }

            if (procesadas > 0) {
                log.info("GTFS-RT Alerts Madrid sincronizadas: {} incidencias oficiales activas", procesadas);
            }
        } catch (Exception ex) {
            log.warn("No se pudo sincronizar Alerts GTFS-RT: {}", ex.getMessage());
        }
    }

    private FeedMessage descargarFeedProtobuf(String url) throws Exception {
        URI uri = URI.create(url);
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            log.warn("URL GTFS-RT rechazada por no usar HTTPS: {}", url);
            return null;
        }

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "ByPass-Mobility-Backend/1.0")
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            log.warn("Feed GTFS-RT ({}) devolvió código HTTP {}", url, response.statusCode());
            return null;
        }

        try (InputStream is = response.body()) {
            return FeedMessage.parseFrom(is);
        }
    }

    private Linea resolverLineaMadrid(String routeId, String tripId, Map<String, Linea> lineasPorId) {
        if (routeId != null && !routeId.isBlank()) {
            Linea exacta = lineasPorId.get("madrid_renfe_" + routeId);
            if (exacta != null) return exacta;
        }

        String referencia = (routeId != null && !routeId.isBlank() ? routeId : tripId).toUpperCase();
        return lineasPorId.values().stream()
                .filter(l -> l.getIdLinea().startsWith("madrid_renfe_"))
                .sorted((a, b) -> Integer.compare(b.getCodigoCorto().length(), a.getCodigoCorto().length()))
                .filter(l -> referencia.contains(l.getCodigoCorto().replace("-", "").toUpperCase()))
                .findFirst()
                .orElse(null);
    }
    private String extraerTextoAlerta(Alert alert) {
        String header = obtenerTraduccionEs(alert.getHeaderText());
        String desc = obtenerTraduccionEs(alert.getDescriptionText());
        if (!header.isBlank() && !desc.isBlank() && !desc.contains(header)) {
            return (header + ": " + desc).replaceAll("\\s+", " ").trim();
        }
        return (!desc.isBlank() ? desc : header).replaceAll("\\s+", " ").trim();
    }

    private String obtenerTraduccionEs(TranslatedString ts) {
        if (ts == null || ts.getTranslationCount() == 0) return "";
        return ts.getTranslationList().stream()
                .filter(t -> "es".equalsIgnoreCase(t.getLanguage()))
                .map(TranslatedString.Translation::getText)
                .findFirst()
                .orElse(ts.getTranslation(0).getText());
    }

    private String clasificarTipoAlerta(Alert alert, String texto) {
        String lower = texto.toLowerCase();
        if (lower.contains("corte") || lower.contains("interrump") || lower.contains("suspendid") || alert.getEffect() == Alert.Effect.NO_SERVICE) {
            return "CORTE_VIA";
        }
        if (lower.contains("infraestructura") || lower.contains("señalización") || lower.contains("catenaria")) {
            return "INFRAESTRUCTURA";
        }
        if (lower.contains("avería") || lower.contains("averia") || lower.contains("incidencia técnica")) {
            return "AVERIA_TREN";
        }
        if (lower.contains("demora") || lower.contains("retraso") || alert.getEffect() == Alert.Effect.SIGNIFICANT_DELAYS) {
            return "RETRASO";
        }
        return "OTRO";
    }

    private String limitarLongitud(String texto, int max) {
        return texto.length() <= max ? texto : texto.substring(0, max - 3) + "...";
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
                    .nivelSaturacion("SIN_DATOS")
                    .scorePonderado(BigDecimal.ZERO)
                    .build());
        }
    }

    private String sanitizarId(String raw) {
        String limpio = raw.replaceAll("[^A-Za-z0-9_-]", "_");
        return limpio.length() > 50 ? limpio.substring(0, 50) : limpio;
    }
}