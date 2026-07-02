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
| Jaeger (direct) | <http://localhost:16686/search?service=wireglass-loadtest-stand> | One span per request from the stand — populated as soon as the stand handles any traffic (see [Tracing](infra/wireglass-loadtest-stand/README.md#tracing)) |
| Grafana → Jaeger (Explore) | [same traces, through Grafana](http://localhost:3000/explore?schemaVersion=1&panes=%7B%22trc%22%3A%7B%22datasource%22%3A%22jaeger%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22datasource%22%3A%7B%22type%22%3A%22jaeger%22%2C%22uid%22%3A%22jaeger%22%7D%2C%22queryType%22%3A%22search%22%2C%22service%22%3A%22wireglass-loadtest-stand%22%7D%5D%2C%22range%22%3A%7B%22from%22%3A%22now-1h%22%2C%22to%22%3A%22now%22%7D%7D%7D&orgId=1) | Verified working; datasource uids are pinned in `datasources.yml` (`jaeger`, `opensearch`, `influxdb`) so this link survives a `docker compose down -v` |
| Grafana → OpenSearch Logs (Explore) | [logs-* index](http://localhost:3000/explore?schemaVersion=1&panes=%7B%22logs%22%3A%7B%22datasource%22%3A%22opensearch%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22datasource%22%3A%7B%22type%22%3A%22elasticsearch%22%2C%22uid%22%3A%22opensearch%22%7D%2C%22query%22%3A%22%22%7D%5D%2C%22range%22%3A%7B%22from%22%3A%22now-1h%22%2C%22to%22%3A%22now%22%7D%7D%7D&orgId=1) | **Empty** — nothing in this repo posts to `logs-*` yet (works, verified reachable, just no data; see `infra/monitoring/README.md` for the ingestion format if you want to wire it up) |
| OpenSearch Dashboards (direct) | <http://localhost:5601> | Same `logs-*` data, OpenSearch's own UI instead of Grafana |
| InfluxDB UI | <http://localhost:8086> | Raw bucket/data explorer, token `wireglass-dev-token` |
| Wireglass-loadtest-stand | <http://localhost:8081/docs> | Swagger UI for the stand's own endpoints |
