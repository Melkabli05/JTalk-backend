# Performance harness

SQL to reproduce the database numbers in the main README ("Performance"). It needs a throwaway PostgreSQL, never a real database.

```bash
docker run -d --name perf-pg -e POSTGRES_PASSWORD=perf -e POSTGRES_DB=perf -p 55432:5432 postgres:18
P="docker exec -i perf-pg psql -q -v ON_ERROR_STOP=1 -U postgres -d perf"
cat ../src/main/resources/db/migration/V*.sql | $P      # run from this folder
$P < 01-seed-rooms.sql
$P < 02-explain.sql
docker rm -f perf-pg
```

The Redis and LiveKit costs are measured by `PerformanceBudgetTest` and `LiveKitCallsTest`, not here.
