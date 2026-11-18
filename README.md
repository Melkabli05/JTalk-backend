# live-communication-service

Real-time **voice rooms**: room lifecycle, stage (speakers + hand-raise queue), presence, grace leases
and low-bandwidth mode.
Java 25 · Micronaut 5 · PostgreSQL · Redis · LiveKit.


## Frontend

The Angular client lives in its own repository: [Melkabli05/JTalk](https://github.com/Melkabli05/JTalk). It talks to this service over `/api`
(its dev server proxies `/api` to `localhost:8080`) and does not depend on this repository's source.

## Project structure

```
src/main/java/com/jtalk/livecommunication/
├── LiveCommunicationApplication.java
├── config/        LiveKitConfig, LiveKitBeanFactory, VoiceRoomProperties, CommunicationConstants, ProblemType (RFC 9457 problems)
├── controller/    VoiceRoomController, StageController, ParticipantController, LiveKitWebhookController
├── service/       service interfaces, the room access validator, and impl/ with their implementations
├── repository/    VoiceRoomRepository (JDBC), the Redis repositories (Lua scripts) and RedisLeaseExpirationListener
├── integration/   LiveKitGateway and impl/LiveKitGatewayImpl
├── domain/        entity/, enums/, event/ (VoiceRoomClosed, LeaseExpired), model/
└── dto/           request/, response/ and the ApiMapper
src/main/resources/  application.yml (+ cloud, quic), db/migration/, lua/
```

## Running locally

`./gradlew run` starts the service with the default configuration; Micronaut Test Resources starts PostgreSQL and Redis in Docker for it. It needs the
Auth0 tenant that issues the tokens and a LiveKit project:

```bash
export AUTH0_DOMAIN=your-tenant.eu.auth0.com      # your Auth0 tenant, without https://
export AUTH0_AUDIENCE=https://api.jtalk.example   # the identifier of the API created in Auth0 (the same one the user service uses)
export LIVEKIT_URL=wss://...    # your LiveKit Cloud project (or a self-hosted server)
export LIVEKIT_API_KEY=...
export LIVEKIT_API_SECRET=...
./gradlew run
```

## Running against the hosted services (`cloud` profile)

`src/main/resources/application-cloud.yml` points the service at hosted free-tier databases, hardcoded on purpose: PostgreSQL on Neon
(the app uses Neon's pooler; Flyway migrates over the direct endpoint) and Redis on Upstash. The Docker image runs with this profile.
Security stays on, so it also needs the JWT secret and the LiveKit project:

```bash
export MICRONAUT_ENVIRONMENTS=cloud AUTH0_DOMAIN=... AUTH0_AUDIENCE=... LIVEKIT_URL=wss://... LIVEKIT_API_KEY=... LIVEKIT_API_SECRET=...
./gradlew run
```

### What the media server enforces

A client can be modified, so the rights in the LiveKit token and in the permission update are what count, not the app's buttons. A listener's token has
`canPublish: false`, so unmuting in a custom client sends nothing the server accepts. A speaker's is limited to the `microphone` source (no camera or
screen share into an audio room). Nobody may write to the data channel (`canPublishData: false`), so only this service can send a room event, and the
frontend also ignores any data message that names a sender. The token lives 5 minutes (`livekit.token-ttl`): it is used within seconds, so a copy taken
from the page is soon useless, and "Reconnect audio" asks the backend for a fresh one. Every host action is checked against the room's host in the
database on each call, and the caller is taken from the verified token, never from the request body.

### Deploying on Render

The `Dockerfile` is what Render builds ([Render: Docker](https://render.com/docs/docker)). The environment is chosen outside the code, by the image's
`MICRONAUT_ENVIRONMENTS=cloud` (Micronaut reads it, or `-Dmicronaut.environments=...`), and the service binds the port Render gives it in `PORT`
(10000 by default, on all interfaces: [Render: web services](https://render.com/docs/web-services)); locally it is 8080.

- **With the Blueprint** (`render.yaml`): in Render, New > Blueprint and pick this repository. Render asks for the two LiveKit secrets
  (`LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`); the Auth0 tenant and audience, `LIVEKIT_URL` and the health check (`/health/liveness`) are set for you.
- **By hand:** New > Web Service, language **Docker**, then set `AUTH0_DOMAIN`, `AUTH0_AUDIENCE` and the three LiveKit variables and the health check path `/health/liveness`.

On the free plan the service sleeps when idle and takes a moment to wake.

**CORS.** A frontend on another origin may call the API (preflights carry no token and are answered). Any origin is allowed until
`CORS_ALLOWED_ORIGINS` (a regular expression, e.g. `https://app\.example\.com|http://localhost:4200`) narrows it.

Two things to know. The repository is public, so those database credentials are public: rotate them in the Neon and Upstash consoles
if they are ever misused. And behind a corporate TLS-inspection proxy (Zscaler and the like) the connection to Upstash can fail with
`PKIX path building failed`; that is the proxy's certificate, not the service: run from another network, or add the proxy's root CA to
the JVM truststore.

## Architecture

Plain layered MVC. The business layer lives under `domain`; HTTP, listeners and the media-server gateway sit beside it.

```
com.jtalk.livecommunication
├── controller            HTTP endpoints, request validation          -> domain.service
├── listener              Redis keyspace-expiry entry point           -> domain.service
├── domain
│   ├── dto/{request,response}
│   ├── service           VoiceRoomService, ParticipantService, StageService (+ webhooks)
│   │   └── impl          implementations (package-private beans)
│   ├── validator         RoomAccessValidator: who may do what (pure rules, throw ProblemType)
│   │   └── impl          implementations (package-private beans)
│   ├── mapper            ApiMapper: domain -> DTO mappings, generated by MapStruct at compile time
│   ├── repository        PostgreSQL (Micronaut Data JDBC) and Redis (Lettuce + Lua)
│   ├── entity            VoiceRoom (persistent, @MappedEntity)
│   ├── enums, model, event
├── integration           LiveKitGateway: the outbound media-server boundary
│   └── impl              LiveKit REST + signed tokens
├── config                typed @ConfigurationProperties, Clock
└── exception             ProblemType enum for all API errors (RFC 9457)
```

`ArchitectureTest` enforces the layering (controller/listener -> service -> validator / repository / integration ->
entity, never upwards; validators never call back into services; only interfaces are used outside `service/impl`,
`validator/impl` and `integration/impl`).

Persistent room metadata (PostgreSQL) is kept separate from ephemeral real-time state (Redis).

Tooling: **Lombok** removes constructor and logger boilerplate (`@RequiredArgsConstructor`, `@Slf4j`); **MapStruct**
generates the domain-to-DTO mappers, and a DTO field with no source fails the build. Processor order in
`build.gradle.kts` matters (Lombok, then MapStruct, then Micronaut's processors).

The four service boundaries:

| Service | Responsibility |
|---|---|
| `VoiceRoomService` | Create / update / close rooms, active-room validation (`getRequiredActiveRoom`), keyset-paginated discovery |
| `ParticipantService` | Join (as a listener, or restoring a seat), leave, host kick, the 30 s grace lease, presence (driven by LiveKit webhooks) and list |
| `StageService` | Claim / leave a seat, hand-raise queue, host promote / demote, low-bandwidth mode |
| `LiveKitGateway` | Rooms, signed tokens, permissions, subscriptions, kicks, data messages |

Services take and return the API DTOs, are `@Validated` at the interface, and use **UUID** user ids.

**Where a rule lives:** a *permission* ("only the host may...", "a kicked user may not return")
is a validator method that works on an already-loaded room, so it is unit-testable and never calls a service. An
*outcome only Redis can decide atomically* (stage full, already on stage) stays in the service that runs the Lua script,
because checking it first would reintroduce the race the script exists to prevent. A rule about *what a user is in a
room* is on `VoiceRoom` (`roleOf`). The rule "when a seat frees up, promote the next raised hand and tell the room" is
`StageService.vacateSeat`, used by leaving the stage, leaving the room and an expired grace lease alike.

Conventions the code follows (and `LiveKitGatewayImplTest` pins down):
- **LiveKit calls go through one gateway.** The SDK returns a Retrofit `Call` that sends nothing until executed, so
  every call is executed there, a failure is decoded with `ServerError` into a 502 problem carrying LiveKit's own code
  and message, and a 404 means "nothing to do". The test runs the real gateway against an in-process server and checks
  each request's path, payload and signing grant.
- **No network I/O inside a database transaction.** Closing a room is `@Transactional` around the write only; Redis
  cleanup and the LiveKit room delete run in `@TransactionalEventListener`s after the commit.
- **One place for API errors** (`ProblemType`), no custom exception classes.

## Features

| Feature | How it works |
|---|---|
| **Stage** | The host is seated when the room is created (seats include the host, 2-12). Anyone can take a free seat (`stage/join`), or raise a hand and wait; the host can promote or demote. Atomic Lua scripts claim a seat and release-and-promote (one call frees a seat and seats the next hand-raiser); they run by SHA and fall back to sending the source after a Redis restart. |
| **30 s grace lease** | LiveKit `participant_left` for a seated speaker creates a Redis key with a TTL instead of freeing the seat. Rejoining (or `participant_joined`) cancels it. When it expires, Redis emits a keyspace event; `LeaseExpirationListener` calls `ParticipantService.handleDisconnectLeaseExpired`, which frees the seat and promotes the next hand-raiser. The host's seat is never reclaimed. The participant list shows `RECONNECTING` meanwhile. |
| **Real-time room events** | Clients do not have to poll. When something changes, the server broadcasts a small JSON event to everyone in the room over LiveKit's reliable data channel (topic `room-events`): `STAGE_UPDATED` (seats changed: refetch `stage`), `HAND_RAISED` / `HAND_LOWERED` (with the user), `SPEAKER_RECONNECTING` / `SPEAKER_RECONNECTED` (a speaker's media dropped / came back within the grace lease), and `REACTIONS` (what people reacted with in the last `voice-room.reaction-batch` (300ms), as a list of `{userId, reaction}`; reactions are never stored, and the throttle is in memory so a refused reaction costs no Redis command). Events carry no state, only `type`, `roomId`, `targetUserId` and `timestamp`. Delivery is best effort: a failed broadcast is logged and never fails the request that caused it. |
| **Webhooks** | `POST /webhooks/livekit`, signature verified with the LiveKit SDK. Events handled: `participant_left`, `participant_joined`; they also decide who is present (there is no heartbeat). LiveKit does not guarantee webhook delivery (it retries, then gives up). |
| **Low-bandwidth mode** | `stage/network-mode`: `LOW_BANDWIDTH` unsubscribes the user from the secondary tracks they list and remembers them; `OPTIMAL` restores them (plus any the request lists). The client decides when it is struggling; nothing to prune means no call to LiveKit. |

## Performance

Measured, not guessed. The database was benchmarked with `EXPLAIN (ANALYZE, BUFFERS)` on 300k rooms, 1.5k of them live (`perf/` reproduces it), and
every hot request is counted by `PerformanceBudgetTest`: Redis **round trips** (what a request waits on over a network; commands sent together share
one) and SQL statements. A regression fails a test instead of showing up as latency under load.

**The database was already fine**, and nothing was changed to "improve" it: the discovery feed is 0.03 ms (0.26 ms filtered by language, 0.03 ms for a
deep page) on the `(status, created_at, id)` and `(status, target_language, created_at, id)` indexes, and a cache-miss room lookup is 0.24 ms. The one
query that could grow into a problem is "does this host already have a live room?", run whenever a room is opened: 0.2 ms with a thousand live rooms but
9 ms with a hundred thousand, because it walked every live room and filtered by host. `V5` adds a small partial index on live rooms by host (0.025 ms at
any size). That is insurance for a bigger platform, not a win today.

**Where the time was:**

| Request | Redis round trips | SQL | Change |
|---|---|---|---|
| `GET /stage` | 2 -> 1 | 0 | the speakers and the queue are read together |
| `GET /participants` | 5 -> 2 | 0 | stage and presence go out together (`RosterRepository`), then one `MGET` of the leases of exactly those people |
| Raise a hand | 4 -> 2 | 0 | the stage snapshot that tells the room is one read |
| Take a seat | 5 -> 3 | 0 | same |
| LiveKit webhook (join / leave) | 3 -> 3 | 1 -> 0 | `findActiveRoom` used to query PostgreSQL every time; it now shares the room cache |
| Burst of webhooks when a room closes | N queries | 1 query | "no such room" is cached too |
| Presence batch (every 2 s) | 1 per changed room | | one pipelined read for all changed rooms |

Join (4 round trips) and leave (3) are left alone on purpose: each step depends on the one before it (ban check, then stage membership, then the host's
claim, then presence, then the room's numbers), so merging them would mean moving business rules into Lua for a small gain.

**LiveKit** is the other cost. Broadcasting a room event used to be a synchronous HTTP call to the media server on the request of the person who
caused it, even though a failed broadcast is only logged. It now runs on its own thread (`@Async`), so a stage change or a hand no longer waits for a round
trip to LiveKit; each event carries the state it announces and a timestamp, so clients keep the newest if two arrive out of order. Every call also has a
hard timeout (`livekit.request-timeout`, default 8 s) instead of OkHttp's 10 s each for connecting, writing and reading, so a slow media server fails a
request with a 502 instead of holding it open. `LiveKitCallsTest` proves both against a media server that takes 2.5 s to answer.

The image runs with `-XX:+UseCompactObjectHeaders`, which shrinks every object on a small instance.


## API

**Secure by default.** Every endpoint (except the LiveKit webhook) needs `Authorization: Bearer <Auth0 access token>`. Auth0 issues the token; this
service checks its RS256 signature against the tenant's JWKS, its issuer, its audience and its expiry (all Micronaut Security configuration), and names
the caller by the token's custom user-id claim (`https://jtalk.app/user_id`, a UUID set by an Auth0 post-login Action; anything else is a 401). The user
service documents the one-time Auth0 setup. Errors are RFC 9457 `application/problem+json` with a stable `type` such as `urn:jtalk:live-communication:problem:stage-full`. LiveKit failures surface as 502 `media-server-error` with LiveKit's own error code in the detail.

| Method & path (under `/api/v1/voice-rooms`) | Who | Purpose |
|---|---|---|
| `POST /` | any | Create a room (`title`, `topic`, `targetLanguage`, `nativeLanguage`, `maxStageSeats`); the caller becomes host, is seated, and gets a session with a LiveKit token. A host who already has an active room gets that room back (re-join) instead of a second one |
| `GET /?targetLanguage=&size=&afterCreatedAt=&afterId=` | any | Active rooms, newest first, keyset-paginated: for the next page pass the last room's `createdAt` and `id`. Each room carries live `speakers`, `participantCount`, `handRaiseCount` (one batched Redis round trip) |
| `GET /feed` | any | The hallway's live view (server-sent events, `text/event-stream`): `READY` first, then `ROOM_OPENED` / `ROOM_UPDATED` (with the room), `ROOM_CLOSED` (with the id) and `ROOM_STATS` (speakers, head count, raised hands, at most every 2 s per room). A client opens it, loads the list once when `READY` arrives, and applies the rest, so it never polls. A ping every 20 s keeps proxies from closing it |
| `GET /{roomId}` / `PATCH /{roomId}` / `DELETE /{roomId}` | any / host / host | Read (with live numbers) / update `title`, `topic`, `maxStageSeats` / close |
| `POST /{roomId}/participants/join` | any | Join: returns the room, a LiveKit token, the role and whether the user may publish |
| `POST .../participants/leave` | member | Leave |
| `GET .../participants` | any | Who is here: role + `ACTIVE`/`RECONNECTING` |
| `DELETE .../participants/{userId}` | host | Kick: frees their seat (promoting the next hand-raiser), disconnects them, revokes their LiveKit tokens and bars them from rejoining |
| `GET .../stage` | any | Speakers (host included) and hand-raise queue |
| `POST .../stage/join` / `POST .../stage/leave` | member | Take a free seat / step down (the host keeps theirs) |
| `POST .../stage/raise-hand` / `DELETE .../stage/raise-hand` | member | Raise (202) / lower hand |
| `POST .../reactions` `{"reaction": "HEART"}` | joined member | Show the room a reaction (`HEART`, `CLAP`, `THUMBS_UP`, `SMILE`, `SPARKLES`, `FIRE`, `PARTY`, `LAUGH`, `WOW`, `THANKS`): 202; 403 `not-in-room` unless joined; 429 `reacting-too-fast` within `voice-room.reaction-interval` (500ms) of your last one |
| `POST .../stage/promote/{userId}` / `POST .../stage/demote/{userId}` | host | Put a user on the stage / step a speaker down |
| `POST .../stage/network-mode` `{mode, secondaryTrackSidsToDrop, primaryTrackSidsToKeep}` | member | Prune / restore secondary tracks (low-bandwidth mode) |
| `POST /webhooks/livekit` | LiveKit | Signed webhook |

## Running

Needs JDK 25 and Docker. PostgreSQL and Redis are started automatically by Micronaut Test Resources for
`./gradlew run` and `./gradlew test`.

**Local development** - `./gradlew run` uses the default configuration (see Running locally above). Profiles are chosen with `MICRONAUT_ENVIRONMENTS`:
`cloud` (hosted Neon and Upstash, hardcoded in `application-cloud.yml`) and `quic` (HTTP/3).

```bash
./gradlew run                                      # local containers, security on
MICRONAUT_ENVIRONMENTS=quic ./gradlew run          # HTTP/3
```

Behind a TLS-inspecting corporate proxy (for example Zscaler) the JVM rejects LiveKit Cloud's re-signed certificate and
room creation fails with `media-server-unreachable` (`PKIX path building failed` in the log). Make the JVM trust the
OS keychain instead of editing `cacerts`: `export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStoreType=KeychainStore -Djavax.net.ssl.trustStore=NONE"`.

**Deployed** - the default profile, security on:

```bash
export AUTH0_DOMAIN=<tenant>.auth0.com AUTH0_AUDIENCE=<API identifier>
export LIVEKIT_URL=wss://<your-livekit-host> LIVEKIT_API_KEY=<key> LIVEKIT_API_SECRET=<secret>
```

Configure LiveKit to send webhooks to `POST <your-host>/webhooks/livekit`.

Deployed environments also set `DATASOURCES_DEFAULT_URL/_USERNAME/_PASSWORD` and `REDIS_URI`.
Tunables with defaults: `voice-room.{presence-timeout,state-ttl,lease-ttl}`, `livekit.token-ttl`.

Redis must have keyspace notifications for expired keys (`notify-keyspace-events` containing `Ex`). The service tries
`CONFIG SET` at startup and logs a warning if that is forbidden (typical on managed Redis); set it there instead.

## Production runtime

- **Who does what.** HTTP/3 here only carries the *control plane* (room and stage calls, faster reconnects after a
  Wi-Fi to 4G switch). Voice packets travel over WebRTC (UDP, TURN-over-TLS as fallback) through LiveKit. Bad
  connections are handled by pruning subscriptions in LiveKit, not by server-side mixing (deliberately not built).
- **JVM:** `java -XX:+UseZGC -XX:+UseCompactObjectHeaders -XX:MaxRAMPercentage=75.0 -jar live-communication-service.jar`
  (generational ZGC is the default in Java 25; compact object headers are a product flag there).
- **Redis:** `notify-keyspace-events Ex` (see Running) so grace leases can expire into seat reclaims.
- **Ports:** the API gateway exposes `8080/TCP` and, with the `quic` profile, `8443/TCP+UDP`. LiveKit nodes need
  `7880/TCP`, `443/TCP` (TURN/TLS) and the configured UDP media range.
- **Databases:** PostgreSQL and Redis never share a transaction; services hold no database transaction while calling
  LiveKit or Redis.
- **Health:** `GET /health/liveness` and `/health/readiness` are anonymous (details hidden). Readiness goes 503 when
  Redis or PostgreSQL is unreachable and recovers by itself; with PostgreSQL down it answers in about 3 s
  (`datasources.default.connection-timeout`), while API calls fail with a 500 after the same 3 s.
- **Verified as a deployment** (built jar, external PostgreSQL and Redis, default profile, credentials only through
  environment variables): Flyway migrates, the service starts in about a second, unauthenticated calls
  answer 401, a real LiveKit room is created, joined, refused to a non-host and closed.

## Before going to production

Not built, or not verified, and worth deciding on:
- **Abandoned rooms stay listed.** LiveKit deletes an empty room after `emptyRoomTimeoutSeconds` (300 s), but nothing
  closes the PostgreSQL row, so a room whose host crashed stays `ACTIVE` in discovery until its host closes it. Handling
  LiveKit's `room_finished` webhook (or a janitor) would close that gap.
- **No rate limiting, CORS or metrics/tracing** are configured. Browser clients on another origin need CORS.
- **One active room per host is not a database constraint**, so two simultaneous creates by one host can open two
  rooms (every lookup tolerates it).
- **The room feed is in memory.** A client hears only about rooms opened, closed or changed on the instance it is connected to, so with
  more than one instance the feed needs a shared channel (Redis pub/sub). Until then, run one instance or have clients reload the list.
- **Several instances are untested.** Each instance listens for lease expiry and a webhook goes to one of them; the
  seat rule is idempotent but this has not been exercised with more than one instance.
- **No load test, no container image build and no HTTP/3 client test** have been run.
- **Rotate the Neon and Upstash credentials** committed in `application-cloud.yml` before the repository is shared (it is public).

## Deliberate decisions and known limits

- **There is no whispering.** The private two-person whisper rooms were removed on request (V4 drops `voice_rooms.allow_whisper`;
  V3, which added it, is kept because migrations are history).

- **Tokens are verified, not issued, here.** This service does not mint user JWTs; it trusts what Auth0 signs for its API, and a token without a UUID
  user-id claim is rejected with 401 (a Micronaut Security claims validator). A kicked user is barred from rejoining that room only.
- **Anyone can take a free seat.** Seats are first come, first served; the hand-raise queue only matters once the stage is
  full, and the host can always promote or demote.
- **Live stats are batched per room, not per request.** The discovery feed reads Postgres once and Redis as one
  pipelined batch. A single Lua call would be one command but breaks Redis Cluster, because each room's keys live in
  a different hash slot.
- **Low-bandwidth pruning is best-effort for auto-subscribing clients.** LiveKit documents no way to stop a client
  re-subscribing to new tracks on its own; for a reliable prune, connect clients with `autoSubscribe` disabled.
- **Webhooks are not guaranteed.** A lost `participant_left` means the lease is never started, so the seat is only
  freed when the user leaves or the host removes them. A lost `participant_left` also leaves that user listed as present until they leave or the room closes; a lost `participant_joined` only means a user who joined is dropped from the list after `voice-room.presence-timeout`.
- **HTTP/3 binds, but no HTTP/3 request was made.** The `quic` profile starts a QUIC (UDP) and a TCP+TLS listener on
  8443 and answers HTTPS; there was no HTTP/3 client available to test the QUIC path itself.
- **Lease expiry depends on Redis keyspace events**, which are not durable: if no instance is listening when a lease
  expires, the seat is not reclaimed until the user leaves or the host removes them.
- Room events are hints, not a log: they can be missed (best-effort delivery, clients that connect late), so clients should refetch `stage` / `participants` on connect and after a reconnect.
- Hand-raising does not verify the user has joined the room.
