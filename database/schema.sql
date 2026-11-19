CREATE DATABASE IF NOT EXISTS bypass_db
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE bypass_db;

-- 1. USUARIOS (Con Trust Score anti-troleo y control de roles)
CREATE TABLE usuario (
    id_usuario BIGINT AUTO_INCREMENT PRIMARY KEY,
    nombre VARCHAR(80) NOT NULL,
    email VARCHAR(120) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    rol ENUM('VIAJERO', 'MODERADOR', 'ADMIN') NOT NULL DEFAULT 'VIAJERO',
    trust_score DECIMAL(4,2) NOT NULL DEFAULT 1.00,
    total_reportes INT NOT NULL DEFAULT 0,
    reportes_validados INT NOT NULL DEFAULT 0,
    activo BOOLEAN NOT NULL DEFAULT TRUE,
    fecha_registro DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_trust_score CHECK (trust_score >= 0.00 AND trust_score <= 5.00)
) ENGINE=InnoDB;

-- 2. ESTACIONES OFICIALES (Compatible con stops.txt de GTFS)
CREATE TABLE estacion (
    id_estacion VARCHAR(32) PRIMARY KEY,
    nombre VARCHAR(120) NOT NULL,
    latitud DECIMAL(10,7) NOT NULL,
    longitud DECIMAL(10,7) NOT NULL,
    nucleo VARCHAR(50) NOT NULL DEFAULT 'MADRID',
    tiene_enlace_metro BOOLEAN NOT NULL DEFAULT FALSE,
    tiene_enlace_bus BOOLEAN NOT NULL DEFAULT FALSE,
    INDEX idx_estacion_nombre (nombre)
) ENGINE=InnoDB;

-- 3. LÍNEAS OFICIALES (Compatible con routes.txt de GTFS)
CREATE TABLE linea (
    id_linea VARCHAR(32) PRIMARY KEY,
    codigo_corto VARCHAR(20) NOT NULL,
    nombre_largo VARCHAR(150) NOT NULL,
    operador VARCHAR(50) NOT NULL DEFAULT 'RENFE_CERCANIAS',
    color_hex VARCHAR(7) NOT NULL DEFAULT '#E60000'
) ENGINE=InnoDB;

-- 4. RECORRIDO Y ORDEN DE PARADAS POR LÍNEA
CREATE TABLE linea_estacion (
    id_linea VARCHAR(32) NOT NULL,
    id_estacion VARCHAR(32) NOT NULL,
    orden_parada INT NOT NULL,
    minutos_desde_origen INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id_linea, id_estacion),
    CONSTRAINT fk_le_linea FOREIGN KEY (id_linea) REFERENCES linea(id_linea) ON DELETE CASCADE,
    CONSTRAINT fk_le_estacion FOREIGN KEY (id_estacion) REFERENCES estacion(id_estacion) ON DELETE CASCADE
) ENGINE=InnoDB;

-- 5. ESTADO Y ALERTAS OFICIALES EN TIEMPO REAL (Alimentado por GTFS-RT cada 30s)
CREATE TABLE alerta_oficial_rt (
    id_alerta_rt VARCHAR(64) PRIMARY KEY,
    id_linea VARCHAR(32) NULL,
    id_estacion VARCHAR(32) NULL,
    titulo VARCHAR(200) NOT NULL,
    descripcion TEXT,
    retraso_medio_seg INT NOT NULL DEFAULT 0,
    activa BOOLEAN NOT NULL DEFAULT TRUE,
    ultima_actualizacion DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_alerta_linea FOREIGN KEY (id_linea) REFERENCES linea(id_linea) ON DELETE SET NULL,
    CONSTRAINT fk_alerta_estacion FOREIGN KEY (id_estacion) REFERENCES estacion(id_estacion) ON DELETE SET NULL
) ENGINE=InnoDB;

-- 6. INCIDENCIAS REPORTADAS POR LA COMUNIDAD (Con gestión hacia el operador)
CREATE TABLE incidencia (
    id_incidencia BIGINT AUTO_INCREMENT PRIMARY KEY,
    id_usuario_reporta BIGINT NOT NULL,
    id_linea VARCHAR(32) NOT NULL,
    id_estacion VARCHAR(32) NULL,
    categoria ENUM('AVERIA_TREN', 'AVERIA_INFRAESTRUCTURA', 'RETRASO_GRAVE', 'CORTE_LINEA', 'ACCESIBILIDAD', 'OTRO') NOT NULL,
    titulo VARCHAR(140) NOT NULL,
    descripcion VARCHAR(500),
    latitud_reporte DECIMAL(10,7) NULL,
    longitud_reporte DECIMAL(10,7) NULL,
    estado ENUM('PENDIENTE', 'VERIFICADA', 'EN_CURSO', 'RESUELTA', 'DESCARTADA') NOT NULL DEFAULT 'PENDIENTE',
    confirmaciones_count INT NOT NULL DEFAULT 1,
    remitida_operador BOOLEAN NOT NULL DEFAULT FALSE,
    fecha_remision DATETIME NULL,
    fecha_creacion DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    fecha_resolucion DATETIME NULL,
    CONSTRAINT fk_inc_usuario FOREIGN KEY (id_usuario_reporta) REFERENCES usuario(id_usuario),
    CONSTRAINT fk_inc_linea FOREIGN KEY (id_linea) REFERENCES linea(id_linea),
    CONSTRAINT fk_inc_estacion FOREIGN KEY (id_estacion) REFERENCES estacion(id_estacion),
    INDEX idx_inc_estado_fecha (estado, fecha_creacion)
) ENGINE=InnoDB;

-- 7. CONFIRMACIONES DE INCIDENCIAS (Evita votos duplicados por usuario)
CREATE TABLE confirma_incidencia (
    id_usuario BIGINT NOT NULL,
    id_incidencia BIGINT NOT NULL,
    comentario VARCHAR(255),
    fecha_confirmacion DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id_usuario, id_incidencia),
    CONSTRAINT fk_conf_usuario FOREIGN KEY (id_usuario) REFERENCES usuario(id_usuario) ON DELETE CASCADE,
    CONSTRAINT fk_conf_incidencia FOREIGN KEY (id_incidencia) REFERENCES incidencia(id_incidencia) ON DELETE CASCADE
) ENGINE=InnoDB;

-- 8. TERMÓMETRO DE SATURACIÓN POR VAGÓN EN TIEMPO REAL
CREATE TABLE reporte_saturacion (
    id_reporte BIGINT AUTO_INCREMENT PRIMARY KEY,
    id_usuario BIGINT NOT NULL,
    id_linea VARCHAR(32) NOT NULL,
    id_estacion VARCHAR(32) NOT NULL,
    zona_tren ENUM('CABEZA', 'CENTRO', 'COLA', 'ANDEN') NOT NULL DEFAULT 'CENTRO',
    nivel_ocupacion ENUM('BAJO', 'MEDIO', 'ALTO', 'SATURADO') NOT NULL,
    fecha_reporte DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_sat_usuario FOREIGN KEY (id_usuario) REFERENCES usuario(id_usuario),
    CONSTRAINT fk_sat_linea FOREIGN KEY (id_linea) REFERENCES linea(id_linea),
    CONSTRAINT fk_sat_estacion FOREIGN KEY (id_estacion) REFERENCES estacion(id_estacion),
    INDEX idx_sat_analitica (id_linea, id_estacion, fecha_reporte)
) ENGINE=InnoDB;

-- 9. AGREGADO ANALÍTICO POR FRANJA HORARIA (Para predicción de horas punta e informes)
CREATE TABLE analitica_franja_horaria (
    id_linea VARCHAR(32) NOT NULL,
    id_estacion VARCHAR(32) NOT NULL,
    dia_semana TINYINT NOT NULL, -- 1=Lunes ... 7=Domingo
    hora_dia TINYINT NOT NULL,   -- 0..23
    total_consultas INT NOT NULL DEFAULT 0,
    total_desvios_pedidos INT NOT NULL DEFAULT 0,
    indice_saturacion_medio DECIMAL(4,2) NOT NULL DEFAULT 0.00,
    PRIMARY KEY (id_linea, id_estacion, dia_semana, hora_dia),
    CONSTRAINT fk_ana_linea FOREIGN KEY (id_linea) REFERENCES linea(id_linea) ON DELETE CASCADE,
    CONSTRAINT fk_ana_estacion FOREIGN KEY (id_estacion) REFERENCES estacion(id_estacion) ON DELETE CASCADE
) ENGINE=InnoDB;

-- 10. BUZÓN DE SUGERENCIAS Y FEEDBACK DE LA APP
CREATE TABLE sugerencia_app (
    id_sugerencia BIGINT AUTO_INCREMENT PRIMARY KEY,
    id_usuario BIGINT NOT NULL,
    tipo ENUM('MEJORA', 'FALLO_APP', 'DATO_INCORRECTO') NOT NULL,
    mensaje TEXT NOT NULL,
    leida BOOLEAN NOT NULL DEFAULT FALSE,
    fecha_envio DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_sug_usuario FOREIGN KEY (id_usuario) REFERENCES usuario(id_usuario)
) ENGINE=InnoDB;
