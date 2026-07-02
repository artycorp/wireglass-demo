package com.artembelikov.wireglassdemo.loadtest;

import static com.artembelikov.listview.client.capture.TrafficCaptureClient.trafficCaptureClient;
import static us.abstracta.jmeter.javadsl.JmeterDsl.httpDefaults;
import static us.abstracta.jmeter.javadsl.JmeterDsl.httpHeaders;
import static us.abstracta.jmeter.javadsl.JmeterDsl.httpSampler;
import static us.abstracta.jmeter.javadsl.JmeterDsl.influxDbListener;
import static us.abstracta.jmeter.javadsl.JmeterDsl.jsonExtractor;
import static us.abstracta.jmeter.javadsl.JmeterDsl.jtlWriter;
import static us.abstracta.jmeter.javadsl.JmeterDsl.responseAssertion;
import static us.abstracta.jmeter.javadsl.JmeterDsl.rpsThreadGroup;
import static us.abstracta.jmeter.javadsl.JmeterDsl.testPlan;

import java.time.Duration;
import org.apache.http.entity.ContentType;
import us.abstracta.jmeter.javadsl.core.TestPlanStats;
import us.abstracta.jmeter.javadsl.core.threadgroups.RpsThreadGroup;

/**
 * Demo scenario for the wireglass-loadtest-stand: exercises all three latency tiers at a steady
 * combined ~50 requests/minute for 10 minutes (~500 requests total -- a slow, continuous burn
 * meant to be watched live in Grafana and the Wireglass packet list rather than a quick burst).
 *
 * <p>Requests are split evenly by <b>iteration</b> across the three tiers (125 iterations each);
 * the medium tier counts double since its login-&gt;orders chain is 2 requests per iteration:
 * fast 125 req, medium 125 iterations = 250 req, slow 125 req -- 500 req / 600s = 50 RPM.
 *
 * <p>Prerequisites (each in its own terminal):
 * <pre>
 *   1. cd ../infra/wireglass-loadtest-stand &amp;&amp; docker compose up -d --build   (host port 8081)
 *   2. cd ../infra/monitoring &amp;&amp; cp .env.example .env &amp;&amp; docker compose up -d
 *   3. In the wireglass repo: mvn install -pl web-listview-client
 *      then: mvn -pl web-listview -am spring-boot:run   (default port 8080)
 * </pre>
 *
 * <p>Run: {@code mvn compile exec:java} from this module's directory. Override endpoints with
 * the {@code STAND_URL}, {@code WIREGLASS_URL}, {@code INFLUX_URL}, {@code INFLUX_TOKEN} env
 * vars if your local ports differ from the infra/ defaults.
 */
public final class WireglassLoadtestScenario {

  private static final String STAND_URL = env("STAND_URL", "http://localhost:8081");
  private static final String WIREGLASS_URL = env("WIREGLASS_URL", "http://localhost:8080");
  // influxDbListener timestamps its points in nanoseconds; precision=ms here would make InfluxDB
  // misread them as an out-of-range date and silently drop every write.
  private static final String INFLUX_URL = env("INFLUX_URL",
      "http://localhost:8086/api/v2/write?org=wireglass&bucket=jmeter&precision=ns");
  private static final String INFLUX_TOKEN = env("INFLUX_TOKEN", "wireglass-dev-token");

  // Defines the 500-request target; HOLD is the actual run length and can be shortened (e.g.
  // for a smoke test) via SCENARIO_HOLD_SECONDS without changing the target rate.
  private static final Duration RPS_REFERENCE = Duration.ofMinutes(10);
  private static final Duration RAMP = Duration.ofSeconds(10);
  private static final Duration HOLD =
      Duration.ofSeconds(envLong("SCENARIO_HOLD_SECONDS", RPS_REFERENCE.getSeconds()));
  private static final double FAST_RPS = requestsToRps(125);
  private static final double MEDIUM_RPS = requestsToRps(250);
  private static final double SLOW_RPS = requestsToRps(125);

  private WireglassLoadtestScenario() {
  }

  public static void main(String[] args) throws Exception {
    TestPlanStats stats = testPlan(
        httpDefaults()
            .url(STAND_URL)
            .connectionTimeout(Duration.ofSeconds(5))
            .responseTimeout(Duration.ofSeconds(10)),

        fastTierGroup(),
        mediumTierGroup(),
        slowTierGroup(),

        influxDbListener(INFLUX_URL)
            .token(INFLUX_TOKEN)
            .application("wireglass-loadtest-stand")
            .measurement("jmeter")
            .tag("scenario", "wireglass-demo"),
        trafficCaptureClient(WIREGLASS_URL),
        jtlWriter("target/jtls")
    ).run();

    System.out.printf(
        "Done. requests=%d errors=%d p95=%s%n",
        stats.overall().samplesCount(),
        stats.overall().errorsCount(),
        stats.overall().sampleTime().perc95());
  }

  private static RpsThreadGroup fastTierGroup() {
    return rpsThreadGroup("fast-tier")
        .maxThreads(3)
        .rampToAndHold(FAST_RPS, RAMP, HOLD)
        .children(
            httpSampler("fast-ping", "/api/fast/ping")
        );
  }

  private static RpsThreadGroup mediumTierGroup() {
    return rpsThreadGroup("medium-tier")
        .maxThreads(3)
        .rampToAndHold(MEDIUM_RPS, RAMP, HOLD)
        .children(
            httpSampler("medium-login", "/api/medium/login")
                .post("{\"username\": \"loadtest\", \"password\": \"secret\"}",
                    ContentType.APPLICATION_JSON)
                .children(
                    jsonExtractor("TOKEN", "token"),
                    responseAssertion().containsSubstrings("token")
                ),
            httpHeaders().header("Authorization", "Bearer ${TOKEN}"),
            httpSampler("medium-orders", "/api/medium/orders/${__Random(1,999)}")
                .children(
                    responseAssertion().containsSubstrings("order_id")
                )
        );
  }

  private static RpsThreadGroup slowTierGroup() {
    return rpsThreadGroup("slow-tier")
        .maxThreads(3)
        .rampToAndHold(SLOW_RPS, RAMP, HOLD)
        .children(
            httpSampler("slow-report", "/api/slow/report?rows=200")
        );
  }

  private static double requestsToRps(int requests) {
    return requests / (double) RPS_REFERENCE.getSeconds();
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return (value == null || value.isBlank()) ? fallback : value;
  }

  private static long envLong(String name, long fallback) {
    String value = System.getenv(name);
    return (value == null || value.isBlank()) ? fallback : Long.parseLong(value);
  }
}
