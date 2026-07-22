# Wireglass Loadtest Stand

A single FastAPI service exposing **three latency tiers** (fast / medium / slow),
deployed via Docker Compose. Purpose: a repeatable load-testing target for
exercising [Wireglass](https://github.com/artycorp/wireglass) — the
traffic under load is what Wireglass's runner captures and displays.

The `login -> token -> orders` chain deliberately forces a correct JMeter test to
use a **JSON extractor**, a **Header Manager** (Bearer auth) and a **response
assertion** — so trivial single-sampler output can't pass.

Host port: `8081` (override with `STAND_PORT` before `docker compose up`)  •  Container port: `8080`  •  Bind: `0.0.0.0`

Defaults to 8081 on the host so it doesn't collide with Wireglass's own default port (8080).

## Endpoints

| Tier | Method & path | ~Latency | Notes |
|------|---------------|----------|-------|
| meta | `GET /health` | 0 | status + tier config |
| meta | `GET /openapi.json` | 0 | OpenAPI 3.1 spec |
| meta | `GET /docs` | 0 | Swagger UI |
| fast | `GET /api/fast/ping` | 20ms | `{id, ts, tier}` |
| medium | `POST /api/medium/login` | 300ms | body `{username, password}` -> `{token, expires_in}` |
| medium | `GET /api/medium/orders/{order_id}` | 300ms | needs `Authorization: Bearer <token>`; 401 without |
| slow | `GET /api/slow/report?rows=N` | 2s | big JSON, `N` capped at `REPORT_MAX_ROWS` |

All latencies have `±JITTER_PCT` random jitter so p95/p99 are meaningful.
Every tier response carries `X-Tier` and `X-Delay-Ms` headers.

## Configuration (`.env`)

| Var | Default | Meaning |
|-----|---------|---------|
| `FAST_MS` | 20 | fast-tier base latency (ms) |
| `MED_MS` | 300 | medium-tier base latency (ms) |
| `SLOW_MS` | 2000 | slow-tier base latency (ms) |
| `JITTER_PCT` | 20 | ± jitter percentage |
| `TOKEN_SECRET` | change-me… | HMAC signing key for tokens |
| `TOKEN_TTL` | 3600 | token lifetime (s) |
| `REPORT_MAX_ROWS` | 5000 | cap for `/api/slow/report` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://host.docker.internal:4318` | Jaeger's OTLP/HTTP port, reached via the host since the stand and `infra/monitoring` are separate compose projects |
| `OTEL_SERVICE_NAME` | `wireglass-loadtest-stand` | service name traces show up under in Jaeger |

Change latency without rebuilding: edit `.env`, then `docker compose up -d`
(env is read at container start).

## Tracing

Every request is auto-instrumented (`opentelemetry-instrumentation-fastapi`) and exported to
Jaeger — open <http://localhost:16686>, service `wireglass-loadtest-stand`. Requires
`infra/monitoring` running; if Jaeger is down the exporter just drops spans, it doesn't fail
requests.

Each response carries a **real** W3C `traceparent` header (and the same value under
`Server-Timing: traceparent;desc=...`) whose trace-id and span-id are the *actual* exported span,
so a captured packet deep-links straight to its trace in Jaeger / SignalFx. This is what the
Wireglass "navigate by header" menu reads. Two consequences worth knowing:

- The correlation middleware reads the live span, which only works because
  `FastAPIInstrumentor` is installed as the **outer** ASGI middleware — see the ordering note
  in `app/main.py` around `instrument_app(app)`. Don't reorder it.
- An incoming `traceparent` request header is continued automatically (the global W3C
  propagator), so the response's trace-id matches the caller's when one is supplied.

`X-Request-ID` is still a separate, hand-minted id for JMeter DSL correlation exercises (echoed
back if the client sends a safe one), independent of the trace.

## Run / modify / redeploy

```bash
# build + start
docker compose up -d --build

# change latency (edit .env), then restart only
docker compose up -d

# logs / status / stop
docker compose logs -f
docker compose ps
docker compose down
```

## Quick smoke test

```bash
BASE=http://localhost:8081

curl -s $BASE/health
curl -s $BASE/api/fast/ping

# correlation chain
TOKEN=$(curl -s -X POST $BASE/api/medium/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"loadtest","password":"secret"}' | jq -r .token)
curl -s $BASE/api/medium/orders/42 -H "Authorization: Bearer $TOKEN"

curl -s "$BASE/api/slow/report?rows=200" | jq '.count'

# spec for the model under test
curl -s $BASE/openapi.json | jq '.info, (.paths | keys)'
```
