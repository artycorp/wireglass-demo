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

## Grafana dashboard

`grafana/provisioning/dashboards/` provisions one dashboard, **Wireglass Loadtest** (folder
`Wireglass Demo`, UID `wireglass-loadtest`), reading the `jmeter` bucket that `loadtest/`'s
`influxDbListener` writes to. Panels: request/error/p95/thread stats, throughput and p95 split by
sampler, percentiles across the whole plan, and errors.

The provider sets `updateIntervalSeconds: 10` and the directory is bind-mounted, so editing the
JSON shows up on a browser refresh — no container restart, unlike `wireglass-config`. Adding a
*new* file still needs `docker compose restart grafana`.

Two things are pinned on purpose, for the same reason the datasource UIDs are:

- The dashboard **UID** `wireglass-loadtest`, because `wireglass-config/demo-dashboards.json`
  deep-links to `/d/wireglass-loadtest/...`. Letting Grafana generate one would break that link on
  every `docker compose down -v`.
- The **errors panel is deliberately not split by sampler.** `influxDbListener` writes
  `countError` only on the `transaction=all` rollup row; per-sampler rows don't carry the field at
  all. A per-sampler errors panel therefore reads `No data` even during a real outage — it looks
  healthy exactly when it shouldn't. Don't "improve" it into a per-sampler breakdown.

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

## Wireglass config (dashboard links + JSON Schema rules)

`wireglass-config/demo-dashboards.json` is a plain nginx-served static file (`wireglass-config`
service, port `8090` by default) in Wireglass's
[server config format](https://github.com/artycorp/wireglass/blob/main/docs/server-config-format.md).
It carries both halves of that format: Jaeger + Grafana Explore links filled in with this stack's
actual ports and the `wireglass-loadtest-stand` service name, and a set of JSON Schema validation
rules for the stand's endpoints. Point a running Wireglass instance at it with:

```bash
mvn -pl wireglass-app -am -DskipTests install
mvn -pl wireglass-app org.springframework.boot:spring-boot-maven-plugin:run \
    -Dspring-boot.run.arguments=--app.listview.remote-config-url=http://localhost:8090/demo-dashboards.json
```

so Settings > Dashboards and Settings > JSON Schema are pre-populated on a clean checkout, with no
manual entry. Edit `wireglass-config/demo-dashboards.json` and restart the `wireglass-config`
container (`docker compose up -d --force-recreate wireglass-config`) to change what's served;
Wireglass re-fetches it on every page load.

**The two halves of this file load by two different paths, and only one of them is a shortcut.**
`dashboards[]` is read server-side by `RemoteConfigService` at startup, so the links exist only if
Wireglass was launched with `--app.listview.remote-config-url`. `schemas[]` can *also* be loaded
without that flag by pasting the URL into Settings > JSON Schema > "Load rules from URL" — but that
path is browser-only and imports the rules alone, storing them in `localStorage`. So a Wireglass
started without the flag shows the eight schema rules and **no dashboard links at all**, which
looks like a broken config and isn't. If the Jaeger/Grafana links are missing, check how the app
was started before editing this file.

### The schema rules are a deliberate pass/fail pair set

Eight rules cover four endpoints. Four are accurate models of what the stand actually returns and
show as **VALID**; four are wrong on purpose and show as **INVALID**, each exercising a different
part of the validator:

| Rule | Target | Result |
|------|--------|--------|
| `✅ Ping response` | response | valid |
| `✅ Login request body` | request | valid |
| `✅ Orders response` | response | valid |
| `✅ Slow report response` | response | valid |
| `❌ Ping response — required field missing` | response | `$.datacenter required field is missing` |
| `❌ Orders response — wrong type on total` | response | `$.total expected string, got number` |
| `❌ Slow report — tier outside enum` | response | `$.tier expected one of …`, `$.count maximum 10 exceeded` |
| `❌ Login request — bounds + missing field` | request | `$.mfa_code required …`, `$.password minLength 12 not met` |

**The four `❌` rules failing is the demo working, not a broken config** — they exist to show the
red state, the error list, and the JSON paths. Run `loadtest/` and open any packet from the three
tiers to see both states side by side on the same body.

Note the validator is a subset of JSON Schema (`type`, `required`, `properties`, `items`, `enum`,
`additionalProperties`, `minimum`/`maximum`, `minLength`/`maxLength`), and it treats an integer as
satisfying `"type": "number"` but not the reverse — so float-valued fields like `total`, `price`
and `value` must be typed `number`, or a whole-numbered response would fail spuriously.

## JMeter Notes

The common demo path is:

- Backend metrics listener or custom code writes load metrics to InfluxDB.
- Test scripts or the tested app emit OTLP traces to Jaeger.
- Test scripts or the tested app emit JSON logs to OpenSearch.

This stack is intentionally separate from the Maven modules. It is only a local integration target for
validating Grafana, Jaeger, and log-view links from the main application.
