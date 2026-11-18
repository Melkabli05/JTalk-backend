-- 300k rooms (298.5k closed, 1.5k live) in the public schema, after migrations V1-V5. psql < this file.
-- 300k rooms over two years: almost all closed (they are never deleted), 1500 live now
insert into voice_rooms (id, title, topic, host_id, target_language, native_language, max_stage_seats, status, created_at, updated_at)
select gen_random_uuid(), 'Room ' || g, null, gen_random_uuid(),
       (array['en','ar','es','fr','de','ja','pt','ru','zh','ko','it','tr'])[1 + (g % 12)],
       (array['ar','en','fr','es','ja','de','ru','pt','ko','zh','tr','it'])[1 + (g % 12)],
       8, case when g <= 1500 then 'ACTIVE' else 'CLOSED' end,
       now() - (random() * interval '730 days'), now()
from generate_series(1, 300000) g;
analyze voice_rooms;
select status, count(*) from voice_rooms group by 1 order by 1;
