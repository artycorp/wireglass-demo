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
