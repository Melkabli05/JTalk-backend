# live-communication-service

Real-time **voice rooms**: room lifecycle, stage (speakers + hand-raise queue), presence, grace leases,
whisper, adaptive subscription and an off-heap mixer.
Java 25 · Micronaut 5 · PostgreSQL · Redis · LiveKit.


## Architecture

Plain layered MVC. The business layer lives under `domain`; HTTP, listeners and external clients sit beside it.

```
com.jtalk.livecommunication
├── controller            HTTP endpoints, request validation          -> domain.service
├── listener              Redis keyspace-expiry entry point           -> domain.service
├── domain
│   ├── dto/{request,response}
│   ├── service           use-case interfaces
│   │   └── impl          implementations (package-private beans; LiveKit REST/JWT lives here)
│   ├── repository        PostgreSQL (Micronaut Data JDBC) and Redis (Lettuce + Lua)
│   ├── entity            VoiceRoom (persistent, @MappedEntity)
│   ├── enums, model, event
├── integration           passive clients of external systems (Redis Stream producer for the mixer)
├── config                typed @ConfigurationProperties, Clock
├── security, exception   caller-identity header; ProblemType enum for all API errors (RFC 9457)
└── mixer                 Media Plane: separate entry point, plain Java (no Micronaut), self-contained
```

`ArchitectureTest` enforces the layering (controller/listener -> service -> repository/integration -> entity,
never upwards; only interfaces are used outside `service/impl`) and that `mixer` depends on nothing of the
control plane except the shared enums and the assignment record.

Persistent room metadata (PostgreSQL) is kept separate from ephemeral real-time state (Redis).

Conventions the code follows (and `LiveKitMediaServiceTest` pins down):
- **LiveKit calls go through one adapter** (`LiveKitMediaService`). The SDK returns a Retrofit `Call` that sends
  nothing until executed, so every call is executed there, a failure is decoded with `ServerError` into a 502
  problem carrying LiveKit's own code and message, and a 404 means "nothing to do". The test runs the real adapter
  against an in-process server and checks each request's path, payload and signing grant.
- **No network I/O inside a database transaction.** Services use no `@Transactional`; each repository call is its own
  short transaction, and LiveKit/Redis calls happen outside it. Events are published only after the write.
- **One place for API errors** (`ProblemType`), no custom exception classes.

## Features

| Feature | How it works |
|---|---|
| **Stage** | Atomic Lua scripts for hand-raise, approval, and release-and-promote (one call frees a seat and seats the next hand-raiser); scripts run by SHA and fall back to sending the source after a Redis restart. Host never needs a seat. |
| **30 s grace lease** | LiveKit `participant_left` for a seated speaker creates a Redis key with a TTL instead of freeing the seat. Rejoining (or `participant_joined`) cancels it. When it expires, Redis emits a keyspace event; `LeaseExpirationListener` frees the seat and promotes the next hand-raiser. The participant list shows `RECONNECTING` meanwhile. |
| **Webhooks** | `POST /webhooks/livekit`, signature verified with the LiveKit SDK. Events handled: `participant_left`, `participant_joined`, `track_published` (whisper tracks). LiveKit does not guarantee webhook delivery (it retries, then gives up). |
| **Whisper** | Speaker -> one present participant. The server validates and stores the session, sends a `whisper` data message to both clients, and when the speaker publishes a `whisper_target_<id>` track it unsubscribes everyone except speaker and target (`UpdateSubscriptions`). |
| **Adaptive subscription** | Clients POST quality reports. Loss > 15 % or downstream < 25 kbps moves that listener from the speaker tracks to the `mcu_mono_fallback` track (server-side `updateSubscriptions`); loss < 3 % for 20 s moves them back. The mixer is requested while anyone is degraded. A listener is never unsubscribed until the fallback track actually exists. |
| **Audience broadcast** | At `voice-room.mixer.audience-threshold` present participants (default 100) a `BROADCAST` mix is requested; it stops below 80 % of that. |
| **Mixer (Media Plane)** | `./gradlew runMixer`. Consumes START/STOP assignments from the Redis Stream `mixer:assignments`, mixes speakers in native memory (FFM ring buffers, soft-knee clipping) every 20 ms and encodes with `libopus` through FFM (8 kbps fallback, 32 kbps broadcast). |

## API

Authentication is done upstream; the caller identity is read from the `X-User-Id` header.
Never expose this service directly to clients. Errors are RFC 9457 `application/problem+json` with a stable `type` such as `urn:jtalk:live-communication:problem:stage-full`. LiveKit failures surface as 502 `media-server-error` with LiveKit's own error code in the detail.

| Method & path (under `/api/v1/voice-rooms`) | Who | Purpose |
|---|---|---|
| `POST /` | any | Create a room (caller becomes host) |
| `GET /?page=&size=` | any | List active rooms, newest first, each with live `speakers`, `participantCount`, `handRaiseCount` (one batched Redis round trip) |
| `GET /{roomId}` / `PATCH /{roomId}` / `DELETE /{roomId}` | any / host / host | Read (with live numbers) / update `name`, `maxSpeakers` / close |
| `POST /{roomId}/participants/me` | any | Join: LiveKit URL, token, role |
| `POST .../participants/me/heartbeat` / `DELETE .../participants/me` | member | Keep alive / leave |
| `GET .../participants` | any | Who is here: role + `ACTIVE`/`RECONNECTING` |
| `GET .../stage` | any | Speakers and hand-raise queue |
| `POST .../stage/hand-raises` / `DELETE .../hand-raises/me` | listener | Raise / lower hand |
| `PUT .../stage/speakers/{userId}` / `DELETE ...` | host / self | Approve / remove or step down |
| `POST .../whisper` `{targetUserId}` / `DELETE .../whisper` | speaker | Start / stop a whisper |
| `POST .../quality` `{lossPercent, downstreamKbps}` | member | Report downstream quality |
| `POST /webhooks/livekit` | LiveKit | Signed webhook |

## Running

Needs JDK 25 and Docker. PostgreSQL and Redis are started automatically by Micronaut Test Resources for
`./gradlew run` and `./gradlew test`. LiveKit is the Cloud project configured in `application.yml`; the secret
comes from the environment:

```bash
export LIVEKIT_API_SECRET=<secret of the LiveKit Cloud key>
./gradlew run                                   # control plane
MICRONAUT_ENVIRONMENTS=quic ./gradlew run       # + HTTPS and HTTP/3 on :8443 (self-signed, local only)
REDIS_URI=redis://localhost:6379 ./gradlew runMixer   # media plane (needs a RoomAudioTransport, see below)
```

Configure LiveKit to send webhooks to `POST <your-host>/webhooks/livekit`.

Deployed environments set `DATASOURCES_DEFAULT_URL/_USERNAME/_PASSWORD`, `REDIS_URI` and the `LIVEKIT_*` variables.
Tunables with defaults: `voice-room.{presence-timeout,state-ttl,lease-ttl,whisper-ttl}`,
`voice-room.quality.{degraded-loss-percent,degraded-downstream-kbps,recovery-loss-percent,recovery-window}`,
`voice-room.mixer.{stream,fallback-track-name,audience-threshold}`, `livekit.token-ttl`.

Redis must have keyspace notifications for expired keys (`notify-keyspace-events` containing `Ex`). The service tries
`CONFIG SET` at startup and logs a warning if that is forbidden (typical on managed Redis); set it there instead.

## Production runtime

- **Who does what.** HTTP/3 here only carries the *control plane* (room and stage calls, faster reconnects after a
  Wi-Fi to 4G switch). Voice packets travel over WebRTC (UDP, TURN-over-TLS as fallback) through LiveKit. Bad
  connections are handled by pruning subscriptions in LiveKit, not by server-side mixing.
- **JVM:** `java -XX:+UseZGC -XX:+UseCompactObjectHeaders -XX:MaxRAMPercentage=75.0 -jar live-communication-service.jar`
  (generational ZGC is the default in Java 25; compact object headers are a product flag there).
- **Redis:** `notify-keyspace-events Ex` (see Running) so grace leases can expire into seat reclaims.
- **Ports:** the API gateway exposes `8080/TCP` and, with the `quic` profile, `8443/TCP+UDP`. LiveKit nodes need
  `7880/TCP`, `443/TCP` (TURN/TLS) and the configured UDP media range.
- **Databases:** PostgreSQL and Redis never share a transaction; services hold no database transaction while calling
  LiveKit or Redis.

## What is not finished, and why

- **The mixer cannot reach a room yet.** LiveKit's documented realtime SDKs are web, Swift, Android, Flutter, React Native, Unity, Python, Node.js, Rust, C++ and ESP32 (Java is only a server-API SDK), so joining a room as a hidden bot and
  publishing a track needs a WebRTC stack that is not in this repository. The mixer defines the seam
  (`RoomAudioTransport`, loaded via `ServiceLoader`) and refuses to start without an implementation rather than
  acknowledging assignments it cannot carry out. The audience tier additionally needs a MoQ / LL-HLS relay
  implementation of the same interface. Until both exist, `FALLBACK`/`BROADCAST` assignments are produced and
  consumed correctly but no audio flows, and adaptive subscription keeps listeners on the speaker tracks.
- **`NativeOpusEncoder` is untested against a real `libopus`.** Its test is skipped where libopus is not installed
  (`brew install opus`, then run `./gradlew test`). The mixing, ring buffer and clipping code is covered.
- **Whisper privacy has a small leak window.** LiveKit documents no per-track permission API, so exclusivity is
  enforced after the fact: the server unsubscribes everyone else when the `track_published` webhook arrives. Until
  then (and for anyone who joins while the whisper is running) clients with the default `autoSubscribe` may receive
  the audio. For strict privacy, connect clients with `autoSubscribe` disabled and subscribe them server-side. The
  same applies to the adaptive subscription swap: it is reliable only for clients that do not auto-subscribe.
- **Webhooks are not guaranteed.** A lost `participant_left` means the lease is never started, so the seat is only
  freed when the user leaves or the host removes them.
- **HTTP/3 binds, but no HTTP/3 request was made.** The `quic` profile starts a QUIC (UDP) and a TCP+TLS listener on
  8443 and answers HTTPS; there was no HTTP/3 client available to test the QUIC path itself.
- **Lease expiry depends on Redis keyspace events**, which are not durable: if no instance is listening when a lease
  expires, the seat is not reclaimed until the user leaves or the host removes them.
- No push channel exists for `USER_RECONNECTING`; clients see `RECONNECTING` by polling the participant list. The
  promotion "token prewarm" is not separate: an approved speaker already holds publish rights, and re-joining issues
  a publishing token.
- Hand-raising does not verify the user has joined the room. Virtual-thread tuning was not changed.
