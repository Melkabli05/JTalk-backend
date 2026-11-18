-- "Does this host already have a live room?" runs whenever someone opens a room. It had to walk every live room and filter by host:
-- 0.2 ms with a thousand live rooms, 9 ms with a hundred thousand. This keeps it a single lookup however busy the platform gets
-- (0.025 ms either way). Only live rooms are indexed, so it stays small however many closed rooms pile up.
CREATE INDEX idx_voice_rooms_active_host ON voice_rooms (host_id) WHERE status = 'ACTIVE';
