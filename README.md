# Wireglass Demo

Demo infrastructure for [Wireglass](https://github.com/artycorp/wireglass) — a web traffic
inspector built on top of `jmeter-java-dsl`. Everything here is a standalone target you point
Wireglass at; none of it is part of the Wireglass product itself.

## Layout

```
infra/
├── wireglass-loadtest-stand/   FastAPI backend with three latency tiers (fast/medium/slow),
│                               a login -> token -> orders correlation chain, and per-request
│                               X-Request-ID / Server-Timing / traceparent headers. This is the
│                               thing Wireglass captures traffic from while it's under load.
│                               Host port 8081.
└── monitoring/                 Grafana + InfluxDB + Jaeger + OpenSearch via Docker Compose —
                                 an observability stack for demoing metrics/traces/log links.

loadtest/                       jmeter-java-dsl scenario that loads the stand, publishes metrics
                                 to InfluxDB, and streams captured packets to a running Wireglass
                                 instance — see loadtest/README.md.
```

Each subdirectory is a self-contained stack with its own README; run them independently.

## Quick start

```bash
# 1. bring up the load-testing target (host port 8081)
cd infra/wireglass-loadtest-stand && docker compose up -d --build

# 2. bring up the observability stack (optional; Grafana on :3000, InfluxDB on :8086)
cd ../monitoring && cp .env.example .env && docker compose up -d

# 3. run Wireglass itself (default port 8080) — in the wireglass repo:
#      mvn install -pl web-listview-client
#      mvn -pl web-listview -am spring-boot:run

# 4. run the demo load scenario against all of the above
cd ../../loadtest && mvn compile exec:java
```

See `infra/wireglass-loadtest-stand/README.md`, `infra/monitoring/README.md`, and
`loadtest/README.md` for endpoint details, configuration, and smoke tests.

## Local service links

Once the stacks above are running:

| Service | URL | What to check |
|---|---|---|
| Wireglass | <http://localhost:8080> | Captured packets streaming in live from `loadtest/` |
| Grafana | <http://localhost:3000> (`admin`/`admin`) | `jmeter` InfluxDB bucket filling in as the scenario runs |
| OpenSearch Dashboards | <http://localhost:5601> | Log documents posted to `logs-*` indices (see `infra/monitoring/README.md`) |
| Jaeger | <http://localhost:16686> | **Empty by default** — nothing in this repo emits spans yet (no B3 headers on requests, no OTLP export from the stand); wire that up first if you need trace data here |
| InfluxDB UI | <http://localhost:8086> | Raw bucket/data explorer, token `wireglass-dev-token` |
| Wireglass-loadtest-stand | <http://localhost:8081/docs> | Swagger UI for the stand's own endpoints |
