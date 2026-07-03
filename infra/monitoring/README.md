# Wireglass Demo Infra

Minimal local observability stack for demo integrations from JMeter, Python services, and the
Wireglass app.

## Services

| Service | URL | Purpose |
| --- | --- | --- |
| Grafana | <http://localhost:3000> | Dashboards for metrics, traces, and logs |
| InfluxDB | <http://localhost:8086> | JMeter/load-test metrics |
| Jaeger | <http://localhost:16686> | Distributed traces |
| OpenSearch | <http://localhost:9200> | Log storage |
| OpenSearch Dashboards | <http://localhost:5601> | Direct log exploration |
| Wireglass config | <http://localhost:8090> | Serves `wireglass-config/demo-dashboards.json` — see below |

Default Grafana login is `admin` / `admin`. Anonymous admin access is enabled for local demo use.

## Run

```bash
cd infra
cp .env.example .env
docker compose up -d
```

Stop the stack:

```bash
cd infra
docker compose down
```

Remove persisted demo data:

```bash
cd infra
docker compose down -v
```

## Endpoints For Test Producers

InfluxDB v2 line protocol:

```text
POST http://localhost:8086/api/v2/write?org=wireglass&bucket=jmeter&precision=ms
Authorization: Token wireglass-dev-token
```

Example:

```bash
curl -i \
  "http://localhost:8086/api/v2/write?org=wireglass&bucket=jmeter&precision=ms" \
  -H "Authorization: Token wireglass-dev-token" \
  --data-binary 'jmeter,scenario=demo,method=GET status=200i,elapsed_ms=123i'
```

Jaeger accepts OpenTelemetry traces:

```text
OTLP gRPC: http://localhost:4317
OTLP HTTP: http://localhost:4318
Jaeger gRPC: localhost:14250
Jaeger HTTP Thrift: http://localhost:14268/api/traces
```

OpenSearch log ingestion:

```text
POST http://localhost:9200/logs-demo/_doc
Content-Type: application/json
```

Example:

```bash
curl -i \
  -H "Content-Type: application/json" \
  -X POST "http://localhost:9200/logs-demo/_doc" \
  -d '{"@timestamp":"2026-07-02T00:00:00Z","level":"INFO","service":"python-demo","trace_id":"demo-trace","message":"demo log event"}'
```

Grafana datasources are provisioned automatically:

- `InfluxDB` reads the `wireglass` org and `jmeter` bucket.
- `Jaeger` points at the local Jaeger query endpoint.
- `OpenSearch Logs` reads daily `logs-*` indices by `@timestamp`.

## Python Notes

For metrics, write InfluxDB line protocol directly or use the official InfluxDB client with:

```text
url=http://localhost:8086
token=wireglass-dev-token
org=wireglass
bucket=jmeter
```

For traces, configure an OpenTelemetry OTLP exporter to `http://localhost:4318`.

For logs, POST JSON documents to an index matching `logs-*`, for example `logs-python-demo`.

## Wireglass Dashboard Links config

`wireglass-config/demo-dashboards.json` is a plain nginx-served static file (`wireglass-config`
service, port `8090` by default) in Wireglass's
[server config format](https://github.com/artycorp/wireglass/blob/main/docs/server-config-format.md):
Jaeger + Grafana Explore links, filled in with this stack's actual ports and the
`wireglass-loadtest-stand` service name. Point a running Wireglass instance at it with:

```bash
mvn -pl web-listview -am spring-boot:run \
    -Dspring-boot.run.arguments=--app.listview.remote-config-url=http://localhost:8090/demo-dashboards.json
```

so Settings > Dashboards is pre-populated on a clean checkout, with no manual link entry. Edit
`wireglass-config/demo-dashboards.json` and restart the `wireglass-config` container
(`docker compose up -d --force-recreate wireglass-config`) to change what's served; Wireglass
re-fetches it on every page load.

## JMeter Notes

The common demo path is:

- Backend metrics listener or custom code writes load metrics to InfluxDB.
- Test scripts or the tested app emit OTLP traces to Jaeger.
- Test scripts or the tested app emit JSON logs to OpenSearch.

This stack is intentionally separate from the Maven modules. It is only a local integration target for
validating Grafana, Jaeger, and log-view links from the main application.
