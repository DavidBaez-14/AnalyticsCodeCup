-- ============================================================
--  MS4 Analytics — Schema inicial
--  Ejecutar UNA SOLA VEZ en la nueva BD Supabase #3 (analytics).
-- ============================================================

CREATE SCHEMA IF NOT EXISTS analytics;

-- Snapshot de posiciones (por grupo o GLOBAL para LIGA)
CREATE TABLE IF NOT EXISTS analytics.snapshot_posicion (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    torneo_id UUID NOT NULL,
    grupo VARCHAR(10) NOT NULL,
    equipo_torneo_id UUID NOT NULL,
    equipo_nombre VARCHAR(150) NOT NULL,
    posicion INT NOT NULL,
    pts INT NOT NULL DEFAULT 0,
    pj INT NOT NULL DEFAULT 0,
    pg INT NOT NULL DEFAULT 0,
    pe INT NOT NULL DEFAULT 0,
    pp INT NOT NULL DEFAULT 0,
    gf INT NOT NULL DEFAULT 0,
    gc INT NOT NULL DEFAULT 0,
    dg INT NOT NULL DEFAULT 0,
    rojas INT NOT NULL DEFAULT 0,
    form VARCHAR(20),
    descalificado BOOLEAN NOT NULL DEFAULT FALSE,
    actualizado_en TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_snapshot_pos_torneo
    ON analytics.snapshot_posicion(torneo_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_snapshot_pos_unique
    ON analytics.snapshot_posicion(torneo_id, equipo_torneo_id);

-- Snapshot de goleadores
CREATE TABLE IF NOT EXISTS analytics.snapshot_goleador (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    torneo_id UUID NOT NULL,
    cedula VARCHAR(20) NOT NULL,
    jugador_nombre VARCHAR(150),
    equipo_nombre VARCHAR(150),
    goles INT NOT NULL DEFAULT 0,
    posicion INT,
    actualizado_en TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_snapshot_gol_torneo
    ON analytics.snapshot_goleador(torneo_id, goles DESC);
CREATE UNIQUE INDEX IF NOT EXISTS idx_snapshot_gol_unique
    ON analytics.snapshot_goleador(torneo_id, cedula);

-- Snapshot de porteros (por equipo)
CREATE TABLE IF NOT EXISTS analytics.snapshot_portero (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    torneo_id UUID NOT NULL,
    equipo_torneo_id UUID NOT NULL,
    equipo_nombre VARCHAR(150),
    goles_en_contra INT NOT NULL DEFAULT 0,
    partidos_jugados INT NOT NULL DEFAULT 0,
    promedio_gc NUMERIC(5,2),
    posicion INT,
    actualizado_en TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_snapshot_portero_unique
    ON analytics.snapshot_portero(torneo_id, equipo_torneo_id);

-- Audit log de webhooks recibidos
CREATE TABLE IF NOT EXISTS analytics.webhook_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tipo VARCHAR(40) NOT NULL,
    torneo_id UUID,
    partido_id UUID,
    payload_json TEXT,
    recibido_en TIMESTAMP NOT NULL DEFAULT NOW(),
    procesado_en TIMESTAMP,
    error TEXT
);
