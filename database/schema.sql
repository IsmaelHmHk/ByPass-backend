-- =============================================================================
-- BYPASS - ESQUEMA MAESTRO DE BASE DE DATOS (PostgreSQL 16+)
-- =============================================================================

-- 1. USUARIOS (Con Trust Score anti-troleo y control de roles)
CREATE TABLE IF NOT EXISTS usuario (
                                       id_usuario BIGSERIAL PRIMARY KEY,
                                       nombre VARCHAR(80) NOT NULL,
    email VARCHAR(120) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    rol VARCHAR(20) NOT NULL DEFAULT 'VIAJERO' CHECK (rol IN ('VIAJERO', 'MODERADOR', 'ADMIN')),
    trust_score DECIMAL(4,2) NOT NULL DEFAULT 1.00 CHECK (trust_score >= 0.00 AND trust_score <= 5.00),
    total_reportes INT NOT NULL DEFAULT 0,
    reportes_validados INT NOT NULL DEFAULT 0,
    activo BOOLEAN NOT NULL DEFAULT TRUE,
    fecha_registro TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
    );

-- 2. ESTACIONES OFICIALES (Compatible con stops.txt de GTFS)
CREATE TABLE IF NOT EXISTS estacion (
                                        id_estacion VARCHAR(32) PRIMARY KEY,
    nombre VARCHAR(120) NOT NULL,
    latitud DECIMAL(10,7) NOT NULL,
    longitud DECIMAL(10,7) NOT NULL,
    nucleo VARCHAR(50) NOT NULL DEFAULT 'MADRID',
    tiene_enlace_metro BOOLEAN NOT NULL DEFAULT FALSE,
    tiene_enlace_bus BOOLEAN NOT NULL DEFAULT FALSE
    );
CREATE INDEX IF NOT EXISTS idx_estacion_nombre ON estacion(nombre);

-- 3. LINEAS OFICIALES (Compatible con routes.txt de GTFS)
CREATE TABLE IF NOT EXISTS linea (
                                     id_linea VARCHAR(32) PRIMARY KEY,
    codigo_corto VARCHAR(20) NOT NULL,
    nombre_largo VARCHAR(150) NOT NULL,
    operador VARCHAR(50) NOT NULL DEFAULT 'RENFE_CERCANIAS',
    color_hex VARCHAR(7) NOT NULL DEFAULT '#E60000'
    );

-- 4. RELACION LINEA - ESTACION (Orden de paradas por linea)
CREATE TABLE IF NOT EXISTS linea_estacion (
                                              id_linea VARCHAR(32) NOT NULL REFERENCES linea(id_linea) ON DELETE CASCADE,
    id_estacion VARCHAR(32) NOT NULL REFERENCES estacion(id_estacion) ON DELETE CASCADE,
    orden_parada INT NOT NULL,
    tiempo_siguiente_min INT,
    PRIMARY KEY (id_linea, id_estacion)
    );

-- 5. TRENES / CIRCULACIONES EN TIEMPO REAL
CREATE TABLE IF NOT EXISTS tren (
                                    id_tren VARCHAR(50) PRIMARY KEY,
    id_linea VARCHAR(32) NOT NULL REFERENCES linea(id_linea),
    destino VARCHAR(120) NOT NULL,
    num_vagones INT NOT NULL DEFAULT 6,
    estado VARCHAR(20) NOT NULL DEFAULT 'EN_HORA' CHECK (estado IN ('EN_HORA', 'RETRASADO', 'SUPRIMIDO', 'DETENIDO')),
    minutos_retraso INT NOT NULL DEFAULT 0,
    ultima_actualizacion TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
    );

-- 6. TERMOMETRO DE SATURACION POR VAGON (Diferencial clave de ByPass)
CREATE TABLE IF NOT EXISTS ocupacion_vagon (
                                               id_ocupacion BIGSERIAL PRIMARY KEY,
                                               id_tren VARCHAR(50) NOT NULL REFERENCES tren(id_tren) ON DELETE CASCADE,
    numero_vagon INT NOT NULL CHECK (numero_vagon >= 1 AND numero_vagon <= 12),
    nivel_saturacion VARCHAR(20) NOT NULL CHECK (nivel_saturacion IN ('VACIO', 'MODERADO', 'LLENO', 'COLAPSADO')),
    score_ponderado DECIMAL(5,2) NOT NULL DEFAULT 0.00,
    actualizado_en TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (id_tren, numero_vagon)
    );

-- 7. REPORTES COLABORATIVOS DE INCIDENCIAS (Crowdsourcing verificado)
CREATE TABLE IF NOT EXISTS reporte_incidencia (
                                                  id_reporte BIGSERIAL PRIMARY KEY,
                                                  id_usuario BIGINT NOT NULL REFERENCES usuario(id_usuario),
    id_linea VARCHAR(32) REFERENCES linea(id_linea),
    id_estacion VARCHAR(32) REFERENCES estacion(id_estacion),
    id_tren VARCHAR(50) REFERENCES tren(id_tren),
    tipo_incidencia VARCHAR(30) NOT NULL CHECK (tipo_incidencia IN ('RETRASO', 'AVERIA_TREN', 'INFRAESTRUCTURA', 'AGLOMERACION', 'CORTE_VIA', 'OTRO')),
    descripcion VARCHAR(500),
    peso_confianza DECIMAL(4,2) NOT NULL DEFAULT 1.00,
    estado_verificacion VARCHAR(20) NOT NULL DEFAULT 'PENDIENTE' CHECK (estado_verificacion IN ('PENDIENTE', 'CONFIRMADO', 'DESCARTADO', 'RESUELTO')),
    creado_en TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expira_en TIMESTAMPTZ NOT NULL
    );

-- 8. VOTOS DE VALIDACION ENTRE VIAJEROS
CREATE TABLE IF NOT EXISTS voto_reporte (
                                            id_voto BIGSERIAL PRIMARY KEY,
                                            id_reporte BIGINT NOT NULL REFERENCES reporte_incidencia(id_reporte) ON DELETE CASCADE,
    id_usuario BIGINT NOT NULL REFERENCES usuario(id_usuario) ON DELETE CASCADE,
    es_veridico BOOLEAN NOT NULL,
    votado_en TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (id_reporte, id_usuario)
    );

-- 9. ALTERNATIVAS DE ENRUTAMIENTO DINAMICO (Metro / Bus / Cercanias alternativo)
CREATE TABLE IF NOT EXISTS alternativa_ruta (
                                                id_alternativa BIGSERIAL PRIMARY KEY,
                                                id_estacion_origen VARCHAR(32) NOT NULL REFERENCES estacion(id_estacion),
    id_estacion_destino VARCHAR(32) NOT NULL REFERENCES estacion(id_estacion),
    modo_transporte VARCHAR(20) NOT NULL CHECK (modo_transporte IN ('METRO', 'AUTOBUS_EMT', 'INTERURBANO', 'CERCANIAS_ALT', 'A_PIE')),
    linea_alternativa VARCHAR(50) NOT NULL,
    tiempo_estimado_min INT NOT NULL,
    instrucciones VARCHAR(300) NOT NULL
    );

-- 10. HISTORICO DE INCIDENCIAS PARA EXPORTACION B2B / OPERADORES
CREATE TABLE IF NOT EXISTS log_analitica_operador (
                                                      id_log BIGSERIAL PRIMARY KEY,
                                                      id_linea VARCHAR(32) NOT NULL REFERENCES linea(id_linea),
    id_estacion VARCHAR(32) REFERENCES estacion(id_estacion),
    fecha_evento DATE NOT NULL,
    hora_punta BOOLEAN NOT NULL DEFAULT FALSE,
    tipo_evento VARCHAR(40) NOT NULL,
    duracion_min INT NOT NULL DEFAULT 0,
    usuarios_afectados_est INT NOT NULL DEFAULT 0
    );


CREATE TABLE IF NOT EXISTS refresh_token (
                                             id_token        BIGSERIAL PRIMARY KEY,
                                             id_usuario      BIGINT NOT NULL REFERENCES usuario(id_usuario) ON DELETE CASCADE,
                                             token_hash      VARCHAR(64) NOT NULL UNIQUE,
                                             familia_token   VARCHAR(36) NOT NULL,
                                             expira_en       TIMESTAMPTZ NOT NULL,
                                             revocado        BOOLEAN NOT NULL DEFAULT FALSE,
                                             creado_en       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_refresh_token_usuario ON refresh_token(id_usuario);

ALTER TABLE estacion ADD COLUMN IF NOT EXISTS codigo_parada VARCHAR(20);
ALTER TABLE estacion ADD COLUMN IF NOT EXISTS accesible_pmr BOOLEAN NOT NULL DEFAULT TRUE;
CREATE INDEX IF NOT EXISTS idx_estacion_coords ON estacion(latitud, longitud);

-- Permitir el estado SIN_DATOS en ocupacion_vagon
ALTER TABLE ocupacion_vagon DROP CONSTRAINT IF EXISTS ocupacion_vagon_nivel_saturacion_check;
ALTER TABLE ocupacion_vagon ADD CONSTRAINT ocupacion_vagon_nivel_saturacion_check
    CHECK (nivel_saturacion IN ('SIN_DATOS', 'VACIO', 'MODERADO', 'LLENO', 'COLAPSADO'));