-- Whispering was removed from the service: nothing reads or writes the per-room switch any more.
ALTER TABLE voice_rooms DROP COLUMN allow_whisper;
