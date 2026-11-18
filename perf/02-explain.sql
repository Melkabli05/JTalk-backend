-- The discovery feed, a language-filtered feed, a deep page, the host lookup and a cache-miss room lookup under EXPLAIN ANALYZE.
\set QUIET on
select host_id as h, id as rid from voice_rooms where status='ACTIVE' limit 1 \gset
select created_at as cts, id as cid from voice_rooms where status='ACTIVE' order by created_at desc, id desc offset 800 limit 1 \gset
prepare feed1(int) as select * from voice_rooms where status='ACTIVE' order by created_at desc, id desc limit $1;
prepare feed2(text, int) as select * from voice_rooms where status='ACTIVE' and target_language=$1 order by created_at desc, id desc limit $2;
prepare feed3(timestamptz, uuid, int) as select * from voice_rooms where status='ACTIVE' and (created_at, id) < ($1, $2) order by created_at desc, id desc limit $3;
prepare byhost(uuid) as select * from voice_rooms where host_id=$1 and status='ACTIVE' limit 1;
prepare byid(uuid) as select * from voice_rooms where id=$1 and status='ACTIVE';
\echo '=== feed page 1'
explain (analyze, buffers, costs off, summary on) execute feed1(21);
\echo '=== feed, language filter'
explain (analyze, buffers, costs off, summary on) execute feed2('es', 21);
\echo '=== feed, deep page'
explain (analyze, buffers, costs off, summary on) execute feed3(:'cts', :'cid', 21);
\echo '=== host lookup (createRoom)'
explain (analyze, buffers, costs off, summary on) execute byhost(:'h');
\echo '=== by id + status (cache miss)'
explain (analyze, buffers, costs off, summary on) execute byid(:'rid');
