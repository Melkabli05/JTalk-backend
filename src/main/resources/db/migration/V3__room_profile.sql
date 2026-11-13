-- Rooms become language-exchange rooms with a stage that includes the host (2-12 seats).
ALTER TABLE voice_rooms RENAME COLUMN name TO title;
ALTER TABLE voice_rooms RENAME COLUMN max_speakers TO max_stage_seats;
ALTER TABLE voice_rooms DROP CONSTRAINT IF EXISTS voice_rooms_max_speakers_check;
UPDATE voice_rooms SET max_stage_seats = GREATEST(max_stage_seats, 2);
ALTER TABLE voice_rooms ADD CONSTRAINT voice_rooms_max_stage_seats_check CHECK (max_stage_seats BETWEEN 2 AND 12);

ALTER TABLE voice_rooms ADD COLUMN topic VARCHAR(255);
ALTER TABLE voice_rooms ADD COLUMN target_language VARCHAR(10) NOT NULL DEFAULT 'en';
ALTER TABLE voice_rooms ADD COLUMN native_language VARCHAR(10) NOT NULL DEFAULT 'en';
ALTER TABLE voice_rooms ADD COLUMN allow_whisper BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE voice_rooms ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE voice_rooms ALTER COLUMN target_language DROP DEFAULT;
ALTER TABLE voice_rooms ALTER COLUMN native_language DROP DEFAULT;

ALTER TABLE voice_rooms ALTER COLUMN host_id TYPE UUID USING host_id::uuid;
ALTER TABLE voice_rooms ALTER COLUMN title TYPE VARCHAR(80);
ALTER TABLE voice_rooms DROP COLUMN closed_at;

-- Discovery filters by language and pages by (created_at, id), newest first.
CREATE INDEX idx_voice_rooms_status_language_created ON voice_rooms (status, target_language, created_at DESC, id DESC);
