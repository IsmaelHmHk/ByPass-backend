package com.bypass.backend.service;

import com.bypass.backend.dto.*;
import com.bypass.backend.entity.*;
import com.bypass.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class IncidenciaService {

    private final ReporteIncidenciaRepository reporteRepo;
    private final VotoReporteRepository votoRepo;
    private final UsuarioRepository usuarioRepo;
    private final LineaRepository lineaRepo;
    private final EstacionRepository estacionRepo;
    private final TrenRepository trenRepo;

    private static final BigDecimal UMBRAL_CONFIRMACION = new BigDecimal("3.00");
    private static final BigDecimal UMBRAL_DESCARTE = new BigDecimal("-2.00");
    private static final BigDecimal MAX_TRUST = new BigDecimal("5.00");
    private static final BigDecimal MIN_TRUST = BigDecimal.ZERO;

    @Transactional
    public ReporteResponseDTO crearReporte(Long idUsuario, CrearReporteRequest req) {
        Usuario autor = usuarioRepo.findById(idUsuario)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado"));

        Linea linea = req.idLinea() != null ? lineaRepo.findById(req.idLinea()).orElse(null) : null;
        Estacion estacion = req.idEstacion() != null ? estacionRepo.findById(req.idEstacion()).orElse(null) : null;
        Tren tren = req.idTren() != null ? trenRepo.findById(req.idTren()).orElse(null) : null;

        // Sanitización XSS antes de persistir en base de datos
        String descripcionSegura = req.descripcion() != null
                ? HtmlUtils.htmlEscape(req.descripcion().trim())
                : null;

        String estadoInicial = autor.getTrustScore().compareTo(new BigDecimal("3.50")) >= 0
                ? "CONFIRMADO" : "PENDIENTE";

        ReporteIncidencia reporte = ReporteIncidencia.builder()
                .usuario(autor)
                .linea(linea)
                .estacion(estacion)
                .tren(tren)
                .tipoIncidencia(req.tipoIncidencia())
                .descripcion(descripcionSegura)
                .pesoConfianza(autor.getTrustScore())
                .estadoVerificacion(estadoInicial)
                .expiraEn(OffsetDateTime.now().plusMinutes(45))
                .build();

        autor.setTotalReportes(autor.getTotalReportes() + 1);
        usuarioRepo.save(autor);

        return mapearADTO(reporteRepo.save(reporte));
    }

    @Transactional
    public ReporteResponseDTO votarReporte(Long idUsuario, Long idReporte, VotarReporteRequest req) {
        ReporteIncidencia reporte = reporteRepo.findById(idReporte)
                .orElseThrow(() -> new IllegalArgumentException("Reporte no encontrado"));

        Usuario votante = usuarioRepo.findById(idUsuario)
                .orElseThrow(() -> new IllegalArgumentException("Votante no encontrado"));

        if (reporte.getUsuario().getIdUsuario().equals(votante.getIdUsuario())) {
            throw new IllegalStateException("No puedes votar tu propio reporte");
        }
        if (votoRepo.existsByReporteIdReporteAndUsuarioIdUsuario(idReporte, votante.getIdUsuario())) {
            throw new IllegalStateException("Ya has votado este reporte");
        }

        votoRepo.save(VotoReporte.builder()
                .reporte(reporte)
                .usuario(votante)
                .esVeridico(req.esVeridico())
                .build());

        BigDecimal impacto = votante.getTrustScore();
        BigDecimal nuevoPeso = req.esVeridico()
                ? reporte.getPesoConfianza().add(impacto)
                : reporte.getPesoConfianza().subtract(impacto);

        reporte.setPesoConfianza(nuevoPeso);

        Usuario autor = reporte.getUsuario();
        if ("PENDIENTE".equals(reporte.getEstadoVerificacion())) {
            if (nuevoPeso.compareTo(UMBRAL_CONFIRMACION) >= 0) {
                reporte.setEstadoVerificacion("CONFIRMADO");
                autor.setReportesValidados(autor.getReportesValidados() + 1);
                autor.setTrustScore(autor.getTrustScore().add(new BigDecimal("0.25")).min(MAX_TRUST));
                usuarioRepo.save(autor);
            } else if (nuevoPeso.compareTo(UMBRAL_DESCARTE) <= 0) {
                reporte.setEstadoVerificacion("DESCARTADO");
                autor.setTrustScore(autor.getTrustScore().subtract(new BigDecimal("0.40")).max(MIN_TRUST));
                usuarioRepo.save(autor);
            }
        }

        return mapearADTO(reporteRepo.save(reporte));
    }

    @Transactional(readOnly = true)
    public List<ReporteResponseDTO> listarActivosPorLinea(String idLinea) {
        return reporteRepo.findByLineaIdLineaAndExpiraEnAfterAndEstadoVerificacionInOrderByCreadoEnDesc(
                        idLinea,
                        OffsetDateTime.now(),
                        List.of("PENDIENTE", "CONFIRMADO")
                )
                .stream()
                .map(this::mapearADTO)
                .toList();
    }

    private ReporteResponseDTO mapearADTO(ReporteIncidencia r) {
        return new ReporteResponseDTO(
                r.getIdReporte(),
                r.getUsuario().getNombre(),
                r.getUsuario().getTrustScore(),
                r.getLinea() != null ? r.getLinea().getIdLinea() : null,
                r.getEstacion() != null ? r.getEstacion().getIdEstacion() : null,
                r.getTren() != null ? r.getTren().getIdTren() : null,
                r.getTipoIncidencia(),
                r.getDescripcion(),
                r.getPesoConfianza(),
                r.getEstadoVerificacion(),
                r.getCreadoEn(),
                r.getExpiraEn()
        );
    }
}