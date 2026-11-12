-- Discovery lists active rooms newest first with the id as tie-breaker (see VoiceRoomRepository).
DROP INDEX idx_voice_rooms_status_created_at;
CREATE INDEX idx_voice_rooms_status_created_at_id ON voice_rooms (status, created_at DESC, id DESC);
