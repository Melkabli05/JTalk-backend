CREATE TABLE voice_rooms (
    id           UUID         PRIMARY KEY,
    name         VARCHAR(120) NOT NULL,
    host_id      VARCHAR(64)  NOT NULL,
    max_speakers INT          NOT NULL CHECK (max_speakers BETWEEN 1 AND 12),
    status       VARCHAR(16)  NOT NULL CHECK (status IN ('ACTIVE', 'CLOSED')),
    created_at   TIMESTAMPTZ  NOT NULL,
    closed_at    TIMESTAMPTZ
);

-- Discovery lists active rooms newest-first.
CREATE INDEX idx_voice_rooms_status_created_at ON voice_rooms (status, created_at DESC);
