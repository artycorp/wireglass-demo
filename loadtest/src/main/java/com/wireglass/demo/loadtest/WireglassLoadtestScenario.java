package com.wireglass.demo.loadtest;

import static com.wireglass.listview.client.capture.TrafficCaptureClient.trafficCaptureClient;
import static us.abstracta.jmeter.javadsl.JmeterDsl.httpDefaults;
import static us.abstracta.jmeter.javadsl.JmeterDsl.httpHeaders;
import static us.abstracta.jmeter.javadsl.JmeterDsl.httpSampler;
import static us.abstracta.jmeter.javadsl.JmeterDsl.influxDbListener;
import static us.abstracta.jmeter.javadsl.JmeterDsl.jsonExtractor;
import static us.abstracta.jmeter.javadsl.JmeterDsl.jtlWriter;
import static us.abstracta.jmeter.javadsl.JmeterDsl.responseAssertion;
import static us.abstracta.jmeter.javadsl.JmeterDsl.testPlan;
import static us.abstracta.jmeter.javadsl.JmeterDsl.threadGroup;
import static us.abstracta.jmeter.javadsl.JmeterDsl.throughputTimer;

import java.time.Duration;
import org.apache.http.entity.ContentType;
import us.abstracta.jmeter.javadsl.core.TestPlanStats;
import us.abstracta.jmeter.javadsl.core.threadgroups.DslDefaultThreadGroup;

/**
 * Demo scenario for the wireglass-loadtest-stand: exercises all three latency tiers at a steady
 * combined ~50 requests/minute for 10 minutes (~500 requests total -- a slow, continuous burn
 * meant to be watched live in Grafana and the Wireglass packet list rather than a quick burst).
 *
 * <p>Requests are split evenly by <b>iteration</b> across the three tiers (125 iterations each);
 * the medium tier counts double since its login-&gt;orders chain is 2 requests per iteration:
 * fast 125 req, medium 125 iterations = 250 req, slow 125 req -- 500 req / 600s = 50 RPM.
 *
 * <p>Each tier is a single-threaded group paced by a {@code throughputTimer} in requests per
 * minute. See the comment above {@link #fastTierGroup()} for why this is not an
 * {@code rpsThreadGroup} -- that one silently overshoots on slow endpoints.
 *
 * <p>Prerequisites (each in its own terminal):
 * <pre>
 *   1. cd ../infra/wireglass-loadtest-stand &amp;&amp; docker compose up -d --build   (host port 8081)
 *   2. cd ../infra/monitoring &amp;&amp; cp .env.example .env &amp;&amp; docker compose up -d
 *   3. In the wireglass repo: mvn -pl wireglass-app -am -DskipTests install
 *      then: mvn -pl wireglass-app org.springframework.boot:spring-boot-maven-plugin:run
 *      (default port 8080; the -am install step also refreshes wireglass-client)
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
  private static final Duration RATE_REFERENCE = Duration.ofMinutes(10);
  private static final Duration HOLD =
      Duration.ofSeconds(envLong("SCENARIO_HOLD_SECONDS", RATE_REFERENCE.getSeconds()));
  private static final double FAST_RPM = requestsToRpm(125);
  private static final double MEDIUM_RPM = requestsToRpm(250);
  private static final double SLOW_RPM = requestsToRpm(125);
  // One thread per tier is enough: even the slow tier's ~2s response fits comfortably inside its
  // ~4.8s pacing interval. It also keeps throughputTimer's un-delayed first sample per thread (a
  // documented ConstantThroughputTimer behaviour) down to one extra request per tier.
  private static final int TIER_THREADS = 1;

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

  /*
   * YOU MUST NOT switch these tiers back to `rpsThreadGroup`. It is backed by jpgc's Throughput
   * Shaping Timer, whose gate is `millisSinceLastSecond < cntSent * msecPerReq` with `cntSent`
   * reset to 0 on every wall-clock second boundary -- so the first sample of each second always
   * passes unthrottled. Once a response takes ~1s or more, a thread returning from the sampler
   * almost always lands in a fresh second, finds the counter reset, and fires immediately: the
   * tier degenerates to 1/responseTime and ignores the configured rate entirely. Measured on the
   * slow tier (~2s responses, 0.2083 rps target): 0.511 rps, 2.45x over target, 305 requests
   * instead of 125. jmeter-dsl documents the underlying constraint as "Avoid too low (eg: under 1)
   * values which can cause big waits and don't match the expected RPS" -- and all three tiers here
   * are far below 1 rps.
   *
   * ConstantThroughputTimer (`throughputTimer`) paces in requests per MINUTE, which keeps this
   * profile out of the fractional-rps regime altogether. Same slow tier, same target: 0.222 rps,
   * 1.07x -- the residual being the timer's documented un-delayed first sample per thread.
   */
  private static DslDefaultThreadGroup fastTierGroup() {
    return threadGroup("fast-tier", TIER_THREADS, HOLD,
        throughputTimer(FAST_RPM),
        httpSampler("fast-ping", "/api/fast/ping")
    );
  }

  private static DslDefaultThreadGroup mediumTierGroup() {
    // throughputTimer paces samples, not iterations, so MEDIUM_RPM covers both requests of the
    // login->orders chain: 25 samples/min = 12.5 iterations/min.
    return threadGroup("medium-tier", TIER_THREADS, HOLD,
        throughputTimer(MEDIUM_RPM),
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

  private static DslDefaultThreadGroup slowTierGroup() {
    return threadGroup("slow-tier", TIER_THREADS, HOLD,
        throughputTimer(SLOW_RPM),
        httpSampler("slow-report", "/api/slow/report?rows=200")
    );
  }

  private static double requestsToRpm(int requests) {
    return requests / (double) RATE_REFERENCE.toMinutes();
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
