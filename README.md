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
└── monitoring/                 Grafana + InfluxDB + Jaeger + OpenSearch via Docker Compose —
                                 an observability stack for demoing metrics/traces/log links.
```

Each subdirectory is a self-contained Docker Compose stack with its own README; run them
independently.

## Quick start

```bash
# 1. bring up the load-testing target
cd infra/wireglass-loadtest-stand && docker compose up -d --build

# 2. bring up the observability stack (optional)
cd ../monitoring && cp .env.example .env && docker compose up -d

# 3. point a jmeter-java-dsl scenario or the Wireglass runner at localhost:8080
```

See `infra/wireglass-loadtest-stand/README.md` and `infra/monitoring/README.md` for endpoint
details, configuration, and smoke tests.
