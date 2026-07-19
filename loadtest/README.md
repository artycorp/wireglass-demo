# Wireglass Demo Loadtest Scenario

A `jmeter-java-dsl` scenario ([`WireglassLoadtestScenario`](src/main/java/com/wireglass/demo/loadtest/WireglassLoadtestScenario.java))
that exercises the [wireglass-loadtest-stand](../infra/wireglass-loadtest-stand) and wires up
both halves of the demo:

- **Metrics** → `influxDbListener`, writing to the InfluxDB in [`infra/monitoring`](../infra/monitoring).
- **Packets** → `trafficCaptureClient` (from `wireglass-client`), streaming every captured
  request/response to a running Wireglass instance so you can watch them arrive live.

## Load profile

A steady, low combined rate — **~50 requests/minute for 10 minutes, ~500 requests total** — meant
to be watched live rather than a quick burst. Requests are split evenly by *iteration* across the
three tiers (125 iterations each); the medium tier counts double since its
`login -> orders` chain is 2 requests per iteration:

| Tier | Iterations | Requests | Target RPM |
|------|-----------:|---------:|-----------:|
| fast | 125 | 125 | 12.5 |
| medium (login + orders) | 125 | 250 | 25 |
| slow | 125 | 125 | 12.5 |
| **total** | | **500** | **50** |

Each tier is a single-threaded `threadGroup` paced by a `throughputTimer` in **requests per
minute**. One thread per tier is enough — even the slow tier's ~2s response fits inside its 4.8s
pacing interval.

> The rate is deliberately expressed in RPM rather than via `rpsThreadGroup`. That thread group is
> backed by jpgc's Throughput Shaping Timer, which resets its per-second sample counter on every
> wall-clock second boundary, so the first sample of each second is never throttled. Once a
> response takes ~1s or more, a returning thread almost always lands in a fresh second and fires
> immediately — the tier degenerates to `1/responseTime` and ignores its configured rate. Measured
> on the slow tier: **305 requests instead of 125** (2.4x over), which put the whole run at 818
> instead of 500. jmeter-dsl documents the constraint as *"Avoid too low (eg: under 1) values"*,
> and all three tiers here sit far below 1 RPS. Don't switch back.

## Prerequisites

```bash
# 1. the stand (host port 8081)
cd ../infra/wireglass-loadtest-stand && docker compose up -d --build

# 2. the observability stack (Grafana :3000, InfluxDB :8086)
cd ../infra/monitoring && cp .env.example .env && docker compose up -d

# 3. Wireglass itself (default port 8080) — in the wireglass repo.
#    Two steps: -am install refreshes wireglass-client, then the app runs without -am
#    (a fully-qualified goal with -am would also run against the client, which has no main class).
mvn -pl wireglass-app -am -DskipTests install
mvn -pl wireglass-app org.springframework.boot:spring-boot-maven-plugin:run
```

## Run

```bash
mvn compile exec:java
```

Open Wireglass at <http://localhost:8080> to watch packets arrive, and Grafana at
<http://localhost:3000> (`admin`/`admin`) to watch the `jmeter` InfluxDB bucket fill in.

## Configuration

All endpoints are overridable via environment variables (defaults match `infra/`):

| Var | Default |
|-----|---------|
| `STAND_URL` | `http://localhost:8081` |
| `WIREGLASS_URL` | `http://localhost:8080` |
| `INFLUX_URL` | `http://localhost:8086/api/v2/write?org=wireglass&bucket=jmeter&precision=ns` |
| `INFLUX_TOKEN` | `wireglass-dev-token` |
| `SCENARIO_HOLD_SECONDS` | `600` (10 min) — shorten for a quick smoke run, e.g. `60` |

Example smoke run (~1 minute, ~50 requests):

```bash
SCENARIO_HOLD_SECONDS=60 mvn compile exec:java
```
