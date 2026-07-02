# Wireglass Demo Loadtest Scenario

A `jmeter-java-dsl` scenario ([`WireglassLoadtestScenario`](src/main/java/com/artembelikov/wireglassdemo/loadtest/WireglassLoadtestScenario.java))
that exercises the [wireglass-loadtest-stand](../infra/wireglass-loadtest-stand) and wires up
both halves of the demo:

- **Metrics** → `influxDbListener`, writing to the InfluxDB in [`infra/monitoring`](../infra/monitoring).
- **Packets** → `trafficCaptureClient` (from `web-listview-client`), streaming every captured
  request/response to a running Wireglass instance so you can watch them arrive live.

## Load profile

A steady, low combined rate — **~50 requests/minute for 10 minutes, ~500 requests total** — meant
to be watched live rather than a quick burst. Requests are split evenly by *iteration* across the
three tiers (125 iterations each); the medium tier counts double since its
`login -> orders` chain is 2 requests per iteration:

| Tier | Iterations | Requests | Target RPS |
|------|-----------:|---------:|-----------:|
| fast | 125 | 125 | 0.2083 |
| medium (login + orders) | 125 | 250 | 0.4167 |
| slow | 125 | 125 | 0.2083 |
| **total** | | **500** | **0.8333 (= 50 RPM)** |

Each tier runs as its own `rpsThreadGroup`, ramping to its target rate over 10s and holding it
for the rest of the run.

## Prerequisites

```bash
# 1. the stand (host port 8081)
cd ../infra/wireglass-loadtest-stand && docker compose up -d --build

# 2. the observability stack (Grafana :3000, InfluxDB :8086)
cd ../infra/monitoring && cp .env.example .env && docker compose up -d

# 3. Wireglass itself (default port 8080) — in the wireglass repo:
mvn install -pl web-listview-client
mvn -pl web-listview -am spring-boot:run
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
