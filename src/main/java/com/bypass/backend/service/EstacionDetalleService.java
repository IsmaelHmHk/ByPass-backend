package com.bypass.backend.service;

import com.bypass.backend.dto.EstacionDetalleDTO;
import com.bypass.backend.dto.EstacionDetalleDTO.ConexionIntercambiadorDTO;
import com.bypass.backend.dto.EstacionDetalleDTO.LineaEstadoDTO;
import com.bypass.backend.dto.SalidaDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class EstacionDetalleService {

    private final JdbcTemplate jdbcTemplate;
    private final ProximasSalidasService proximasSalidasService;

    public EstacionDetalleDTO obtenerDetalle(String idEstacion) {
        var estaciones = jdbcTemplate.queryForList(
                "SELECT id_estacion, nombre, accesible_pmr FROM estacion WHERE id_estacion = ?",
                idEstacion
        );
        if (estaciones.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Estación no encontrada: " + idEstacion);
        }

        Map<String, Object> est = estaciones.get(0);
        String nombre = String.valueOf(est.get("nombre"));
        boolean pmr = Boolean.TRUE.equals(est.get("accesible_pmr"));
        String operador = idEstacion.startsWith("madrid_metro_") ? "METRO_MADRID" : "RENFE_CERCANIAS";

        // Líneas distintas que pasan por esta estación
        String sqlLineas = """
            SELECT DISTINCT l.id_linea, l.codigo_corto, l.nombre_largo
            FROM gtfs_stop_time st
            JOIN gtfs_trip t ON t.trip_id = st.trip_id
            JOIN linea l ON l.id_linea = t.id_linea
            WHERE st.id_estacion = ?
            ORDER BY l.codigo_corto ASC
            """;

        List<LineaEstadoDTO> lineas = jdbcTemplate.query(sqlLineas, (rs, rowNum) ->
                new LineaEstadoDTO(
                        rs.getString("id_linea"),
                        rs.getString("codigo_corto"),
                        rs.getString("nombre_largo") != null ? rs.getString("nombre_largo").replaceAll("\\s+", " ").trim() : "",
                        "NORMAL",
                        null
                ), idEstacion);

        // Próximas 6 salidas reales/programadas
        List<SalidaDTO> salidas = proximasSalidasService.obtenerProximasSalidas(idEstacion, 6);

        // Conexiones a pie en intercambiador
        String sqlConexiones = """
            SELECT ie.id_estacion_destino, e.nombre, ie.minutos_caminando, ie.accesible_pmr, ie.descripcion_paso
            FROM intercambiador_enlace ie
            JOIN estacion e ON e.id_estacion = ie.id_estacion_destino
            WHERE ie.id_estacion_origen = ?
            ORDER BY ie.minutos_caminando ASC
            """;

        List<ConexionIntercambiadorDTO> conexiones = jdbcTemplate.query(sqlConexiones, (rs, rowNum) ->
                new ConexionIntercambiadorDTO(
                        rs.getString("id_estacion_destino"),
                        rs.getString("nombre"),
                        rs.getInt("minutos_caminando"),
                        rs.getBoolean("accesible_pmr"),
                        rs.getString("descripcion_paso")
                ), idEstacion);

        return new EstacionDetalleDTO(
                idEstacion,
                nombre,
                operador,
                pmr,
                "NORMAL",
                lineas,
                salidas,
                conexiones
        );
    }
}