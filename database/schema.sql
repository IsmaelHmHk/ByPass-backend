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

-- 1. LÍNEAS MULTIMODALES DE MADRID (Cercanías, Metro, EMT e Interurbanos)
INSERT INTO linea (id_linea, codigo_corto, nombre_largo, color_hex, operador) VALUES
                                                                                  ('MAD_C1', 'C-1', 'Príncipe Pío - Atocha - Chamartín - Aeropuerto T4', '#38bdf8', 'RENFE_CERCANIAS'),
                                                                                  ('MAD_C2', 'C-2', 'Guadalajara - Alcalá - Atocha - Chamartín', '#16a34a', 'RENFE_CERCANIAS'),
                                                                                  ('MAD_C3', 'C-3', 'Aranjuez - Villaverde Bajo - Atocha - Sol - Chamartín', '#9333ea', 'RENFE_CERCANIAS'),
                                                                                  ('MAD_C4', 'C-4', 'Parla - Villaverde Alto - Atocha - Sol - Chamartín - Colmenar/Alcobendas', '#2563eb', 'RENFE_CERCANIAS'),
                                                                                  ('MAD_C5', 'C-5', 'Móstoles El Soto - Atocha - Villaverde Alto - Fuenlabrada - Humanes', '#eab308', 'RENFE_CERCANIAS'),
                                                                                  ('MAD_C7', 'C-7', 'Alcalá - Atocha - Chamartín - Príncipe Pío', '#dc2626', 'RENFE_CERCANIAS'),
                                                                                  ('MAD_C10', 'C-10', 'Villalba - Príncipe Pío - Atocha - Chamartín - Aeropuerto T4', '#84cc16', 'RENFE_CERCANIAS'),
                                                                                  ('METRO_L1', 'L1', 'Pinar de Chamartín - Sol - Atocha - Valdecarros', '#0ea5e9', 'METRO_MADRID'),
                                                                                  ('METRO_L3', 'L3', 'Villaverde Alto - Legazpi - Sol - Moncloa', '#facc15', 'METRO_MADRID'),
                                                                                  ('METRO_L6', 'L6', 'Circular (Moncloa - Príncipe Pío - Méndez Álvaro - Av. América)', '#64748b', 'METRO_MADRID'),
                                                                                  ('EMT_27', '27', 'Embajadores - Atocha - Recoletos - Castellana - Plaza Castilla', '#0284c7', 'EMT_MADRID'),
                                                                                  ('INT_432', '432', 'Madrid (Villaverde Cruce) - Leganés (Montepinos)', '#15803d', 'INTERURBANOS_CRTM')
ON CONFLICT (id_linea) DO NOTHING;

-- 2. ESTACIONES Y PARADAS CON COORDENADAS REALES Y CÓDIGO DE POSTE
INSERT INTO estacion (id_estacion, codigo_parada, nombre, latitud, longitud, accesible_pmr, tiene_enlace_metro, tiene_enlace_bus) VALUES
                                                                                                                                      ('EST_CHAMARTIN', '17000', 'Madrid - Chamartín - Clara Campoamor', 40.47210000, -3.68260000, true, true, true),
                                                                                                                                      ('EST_NUEVOS_MIN', '18001', 'Nuevos Ministerios', 40.44630000, -3.69220000, true, true, true),
                                                                                                                                      ('EST_SOL', '18002', 'Sol (Cercanías y Metro)', 40.41690000, -3.70350000, true, true, true),
                                                                                                                                      ('EST_ATOCHA', '18000', 'Madrid - Atocha Cercanías', 40.40650000, -3.69080000, true, true, true),
                                                                                                                                      ('EST_MENDEZ_ALVARO', '18003', 'Méndez Álvaro (Estación Sur)', 40.39560000, -3.67800000, true, true, true),
                                                                                                                                      ('EST_PRINCIPE_PIO', '10000', 'Príncipe Pío (Intercambiador)', 40.42110000, -3.72040000, true, true, true),
                                                                                                                                      ('EST_EMBAJADORES', '18004', 'Embajadores', 40.40500000, -3.70270000, true, true, true),
                                                                                                                                      ('EST_VILLAVERDE_ALTO', '35001', 'Villaverde Alto (C-4 / C-5 / L3)', 40.34140000, -3.71150000, true, true, true),
                                                                                                                                      ('EST_VILLAVERDE_BAJO', '60100', 'Villaverde Bajo (C-3 / C-4)', 40.35260000, -3.68440000, true, false, true),
                                                                                                                                      ('EST_LEGAZPI', 'M-304', 'Legazpi (Metro L3 / L6)', 40.39120000, -3.69510000, true, true, true),
                                                                                                                                      ('EST_MONCLOA', 'M-312', 'Moncloa (Intercambiador L3 / L6)', 40.43470000, -3.71910000, true, true, true),
                                                                                                                                      ('PAR_EMT_ATOCHA', 'Poste 1166', 'Atocha - Paseo del Prado (EMT 27)', 40.40810000, -3.69240000, true, true, true),
                                                                                                                                      ('PAR_INT_VILLAVERDE', 'Poste 16732', 'Villaverde Cruce - Intercambiador (Bus 432)', 40.34980000, -3.70890000, true, true, true)
ON CONFLICT (id_estacion) DO NOTHING;

-- 3. GRAFO DE CONEXIONES (TRAMOS CON TIEMPOS REALES EN MINUTOS)
INSERT INTO linea_estacion (id_linea, id_estacion, orden_parada, tiempo_siguiente_min) VALUES
-- C-4 (Parla - Villaverde Alto - Villaverde Bajo - Atocha - Sol - Nuevos Ministerios - Chamartín)
('MAD_C4', 'EST_VILLAVERDE_ALTO', 1, 4),
('MAD_C4', 'EST_VILLAVERDE_BAJO', 2, 7),
('MAD_C4', 'EST_ATOCHA', 3, 4),
('MAD_C4', 'EST_SOL', 4, 5),
('MAD_C4', 'EST_NUEVOS_MIN', 5, 4),
('MAD_C4', 'EST_CHAMARTIN', 6, NULL),

-- C-5 (Villaverde Alto - Méndez Álvaro - Atocha - Embajadores)
('MAD_C5', 'EST_VILLAVERDE_ALTO', 1, 9),
('MAD_C5', 'EST_MENDEZ_ALVARO', 2, 3),
('MAD_C5', 'EST_ATOCHA', 3, 3),
('MAD_C5', 'EST_EMBAJADORES', 4, NULL),

-- C-3 (Villaverde Bajo - Atocha - Sol - Nuevos Ministerios - Chamartín)
('MAD_C3', 'EST_VILLAVERDE_BAJO', 1, 7),
('MAD_C3', 'EST_ATOCHA', 2, 4),
('MAD_C3', 'EST_SOL', 3, 5),
('MAD_C3', 'EST_NUEVOS_MIN', 4, 4),
('MAD_C3', 'EST_CHAMARTIN', 5, NULL),

-- C-1 / C-10 (Príncipe Pío - Méndez Álvaro - Atocha - Nuevos Ministerios - Chamartín)
('MAD_C10', 'EST_PRINCIPE_PIO', 1, 9),
('MAD_C10', 'EST_MENDEZ_ALVARO', 2, 3),
('MAD_C10', 'EST_ATOCHA', 3, 7),
('MAD_C10', 'EST_NUEVOS_MIN', 4, 4),
('MAD_C10', 'EST_CHAMARTIN', 5, NULL),

-- Metro L3 (Villaverde Alto - Legazpi - Embajadores - Sol - Moncloa)
('METRO_L3', 'EST_VILLAVERDE_ALTO', 1, 11),
('METRO_L3', 'EST_LEGAZPI', 2, 4),
('METRO_L3', 'EST_EMBAJADORES', 3, 3),
('METRO_L3', 'EST_SOL', 4, 8),
('METRO_L3', 'EST_MONCLOA', 5, NULL),

-- Metro L6 Circular (Príncipe Pío - Legazpi - Méndez Álvaro - Nuevos Ministerios - Moncloa)
('METRO_L6', 'EST_PRINCIPE_PIO', 1, 11),
('METRO_L6', 'EST_LEGAZPI', 2, 3),
('METRO_L6', 'EST_MENDEZ_ALVARO', 3, 14),
('METRO_L6', 'EST_NUEVOS_MIN', 4, 7),
('METRO_L6', 'EST_MONCLOA', 5, NULL),

-- EMT 27 (Embajadores - Atocha - Nuevos Ministerios)
('EMT_27', 'EST_EMBAJADORES', 1, 6),
('EMT_27', 'PAR_EMT_ATOCHA', 2, 16),
('EMT_27', 'EST_NUEVOS_MIN', 3, NULL)
ON CONFLICT DO NOTHING;


TRUNCATE TABLE alternativa_ruta RESTART IDENTITY;

INSERT INTO alternativa_ruta (id_estacion_origen, id_estacion_destino, modo_transporte, linea_alternativa, tiempo_estimado_min, instrucciones) VALUES
                                                                                                                                                   ('EST_VILLAVERDE_ALTO', 'EST_SOL', 'METRO', 'Línea 3 (Amarilla)', 21, 'Alternativa directa sin transbordos desde Villaverde Alto hasta Sol evitando cortes en C-4/C-5.'),
                                                                                                                                                   ('EST_ATOCHA', 'EST_NUEVOS_MIN', 'AUTOBUS_EMT', 'EMT 27 (Poste 1166)', 22, 'Sal por Paseo del Prado (Poste 1166) y toma el 27 directo por Castellana hasta Nuevos Ministerios.'),
                                                                                                                                                   ('EST_VILLAVERDE_ALTO', 'EST_ATOCHA', 'INTERURBANO', 'Interurbano 432 + Metro L3', 28, 'Desde Villaverde Cruce (Poste 16732) enlaza con L3 en Villaverde Alto o Legazpi.');


CREATE TABLE intercambiador_enlace (
>>     id_enlace           SERIAL PRIMARY KEY,
>>     id_estacion_origen  VARCHAR(100) NOT NULL REFERENCES estacion(id_estacion),
>>     id_estacion_destino VARCHAR(100) NOT NULL REFERENCES estacion(id_estacion),
>>     minutos_caminando   INT NOT NULL,
>>     accesible_pmr       BOOLEAN NOT NULL DEFAULT FALSE,
>>     descripcion_paso    VARCHAR(255) -- Ej: "Pasillo subterráneo vestíbulo Renfe - Metro"
>> );
id_estacion : The term 'id_estacion' is not recognized as the name of a cmdlet, function, script file, or operable program. Check the spelling of the name, or if a path was included, verify that the path is correct and try
again.
At line:3 char:67
+ ... acion_origen  VARCHAR(100) NOT NULL REFERENCES estacion(id_estacion),
+                                                             ~~~~~~~~~~~
    + CategoryInfo          : ObjectNotFound: (id_estacion:String) [], CommandNotFoundException
    + FullyQualifiedErrorId : CommandNotFoundException

