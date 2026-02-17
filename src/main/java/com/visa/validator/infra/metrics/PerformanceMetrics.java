package com.visa.validator.infra.metrics;

import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.AtomicLongMap;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.DoubleSummaryStatistics;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Performance metrics collector using Guava utilities for load testing.
 * Tracks throughput, latency, success/failure rates.
 * Exports to Micrometer for Prometheus/Grafana dashboards.
 */
public class PerformanceMetrics {

  private static final Logger log = LoggerFactory.getLogger(PerformanceMetrics.class);

  // Counters using Guava's AtomicLongMap
  private final AtomicLongMap<String> counters = AtomicLongMap.create();

  // Latency samples (recent window)
  private final ConcurrentLinkedQueue<Long> latencySamples = new ConcurrentLinkedQueue<>();
  private static final int MAX_SAMPLES = 10_000;

  // Throughput tracking
  private final Stopwatch uptime = Stopwatch.createStarted();
  private final AtomicLong lastReportTime = new AtomicLong(System.currentTimeMillis());
  private final AtomicLong lastReportCount = new AtomicLong(0);

  // Counter keys
  public static final String TOTAL_PROCESSED = "total_processed";
  public static final String SUCCESS = "success";
  public static final String FAILURE = "failure";
  public static final String VALIDATION_ERROR = "validation_error";
  public static final String PARSE_ERROR = "parse_error";

  // Micrometer metrics (optional)
  private Counter micrometerSuccessCounter;
  private Counter micrometerFailureCounter;
  private Timer micrometerLatencyTimer;

  /**
   * Optionally register with Micrometer for Prometheus/Grafana export.
   */
  public void registerWithMicrometer(MeterRegistry registry) {
    micrometerSuccessCounter = Counter.builder("transactions.processed")
      .tag("status", "success")
      .description("Successfully processed transactions")
      .register(registry);

    micrometerFailureCounter = Counter.builder("transactions.processed")
      .tag("status", "failure")
      .description("Failed transactions")
      .register(registry);

    micrometerLatencyTimer = Timer.builder("transactions.latency")
      .description("Transaction processing latency")
      .register(registry);

    log.info("Micrometer metrics registered for Prometheus export");
  }

  /**
   * Records a successful transaction processing with latency.
   */
  public void recordSuccess(long latencyMicros) {
    counters.incrementAndGet(TOTAL_PROCESSED);
    counters.incrementAndGet(SUCCESS);
    addLatencySample(latencyMicros);

    // Also record to Micrometer if registered
    if (micrometerSuccessCounter != null) {
      micrometerSuccessCounter.increment();
    }
    if (micrometerLatencyTimer != null) {
      micrometerLatencyTimer.record(latencyMicros, TimeUnit.MICROSECONDS);
    }
  }

  /**
   * Records a failed transaction processing.
   */
  public void recordFailure(String errorType) {
    counters.incrementAndGet(TOTAL_PROCESSED);
    counters.incrementAndGet(FAILURE);
    counters.incrementAndGet(errorType);

    // Also record to Micrometer if registered
    if (micrometerFailureCounter != null) {
      micrometerFailureCounter.increment();
    }
  }

  /**
   * Creates a new Stopwatch for timing operations.
   */
  public Stopwatch startTimer() {
    return Stopwatch.createStarted();
  }

  /**
   * Gets elapsed microseconds from a stopwatch.
   */
  public long elapsed(Stopwatch stopwatch) {
    return stopwatch.elapsed(TimeUnit.MICROSECONDS);
  }

  private void addLatencySample(long latencyMicros) {
    latencySamples.offer(latencyMicros);
    // Keep bounded size
    while (latencySamples.size() > MAX_SAMPLES) {
      latencySamples.poll();
    }
  }

  /**
   * Logs current performance statistics.
   */
  public void logStats() {
    long totalProcessed = counters.get(TOTAL_PROCESSED);
    long successCount = counters.get(SUCCESS);
    long failureCount = counters.get(FAILURE);

    // Calculate throughput
    double uptimeSeconds = uptime.elapsed(TimeUnit.MILLISECONDS) / 1000.0;
    double overallThroughput = uptimeSeconds > 0 ? totalProcessed / uptimeSeconds : 0;

    // Calculate recent throughput (last interval)
    long now = System.currentTimeMillis();
    long lastTime = lastReportTime.getAndSet(now);
    long lastCount = lastReportCount.getAndSet(totalProcessed);
    double intervalSeconds = (now - lastTime) / 1000.0;
    double recentThroughput = intervalSeconds > 0 ? (totalProcessed - lastCount) / intervalSeconds : 0;

    // Calculate latency statistics
    DoubleSummaryStatistics latencyStats = latencySamples.stream()
      .mapToDouble(Long::doubleValue)
      .summaryStatistics();

    // Calculate percentiles
    long[] sortedLatencies = latencySamples.stream().mapToLong(Long::longValue).sorted().toArray();
    long p50 = percentile(sortedLatencies, 50);
    long p95 = percentile(sortedLatencies, 95);
    long p99 = percentile(sortedLatencies, 99);

    double successRate = totalProcessed > 0 ? (successCount * 100.0 / totalProcessed) : 0;

    log.info("╔══════════════════════════════════════════════════════════════╗");
    log.info("║                   PERFORMANCE METRICS                        ║");
    log.info("╠══════════════════════════════════════════════════════════════╣");
    log.info("║  Total Processed:    {}                              ║", String.format("%10d", totalProcessed));
    log.info("║  Success:            {}                              ║", String.format("%10d", successCount));
    log.info("║  Failures:           {}                              ║", String.format("%10d", failureCount));
    log.info("║  Success Rate:       {}%                             ║", String.format("%9.2f", successRate));
    log.info("╠══════════════════════════════════════════════════════════════╣");
    log.info("║  Overall Throughput: {} tx/sec                     ║", String.format("%10.2f", overallThroughput));
    log.info("║  Recent Throughput:  {} tx/sec                     ║", String.format("%10.2f", recentThroughput));
    log.info("╠══════════════════════════════════════════════════════════════╣");
    log.info("║  Latency (μs):                                               ║");
    log.info("║    Min:              {}                              ║", String.format("%10.0f", latencyStats.getMin()));
    log.info("║    Avg:              {}                              ║", String.format("%10.2f", latencyStats.getAverage()));
    log.info("║    Max:              {}                              ║", String.format("%10.0f", latencyStats.getMax()));
    log.info("║    P50:              {}                              ║", String.format("%10d", p50));
    log.info("║    P95:              {}                              ║", String.format("%10d", p95));
    log.info("║    P99:              {}                              ║", String.format("%10d", p99));
    log.info("╚══════════════════════════════════════════════════════════════╝");
  }

  private long percentile(long[] sortedValues, int percentile) {
    if (sortedValues.length == 0) return 0;
    int index = (int) Math.ceil(percentile / 100.0 * sortedValues.length) - 1;
    return sortedValues[Math.max(0, Math.min(index, sortedValues.length - 1))];
  }

  /**
   * Gets counter value.
   */
  public long getCount(String key) {
    return counters.get(key);
  }

  /**
   * Resets all metrics.
   */
  public void reset() {
    counters.clear();
    latencySamples.clear();
    uptime.reset().start();
    lastReportTime.set(System.currentTimeMillis());
    lastReportCount.set(0);
    log.info("Performance metrics reset");
  }

  /**
   * Export metrics as JSON for REST API.
   */
  public JsonObject toJson() {
    long totalProcessed = counters.get(TOTAL_PROCESSED);
    long successCount = counters.get(SUCCESS);
    long failureCount = counters.get(FAILURE);
    long validationErrors = counters.get(VALIDATION_ERROR);
    long parseErrors = counters.get(PARSE_ERROR);

    double uptimeSeconds = uptime.elapsed(TimeUnit.MILLISECONDS) / 1000.0;
    double overallThroughput = uptimeSeconds > 0 ? totalProcessed / uptimeSeconds : 0;

    DoubleSummaryStatistics latencyStats = latencySamples.stream()
      .mapToDouble(Long::doubleValue)
      .summaryStatistics();

    long[] sortedLatencies = latencySamples.stream().mapToLong(Long::longValue).sorted().toArray();
    long p50 = percentile(sortedLatencies, 50);
    long p95 = percentile(sortedLatencies, 95);
    long p99 = percentile(sortedLatencies, 99);

    double successRate = totalProcessed > 0 ? (successCount * 100.0 / totalProcessed) : 0;

    return new JsonObject()
      .put("summary", new JsonObject()
        .put("totalProcessed", totalProcessed)
        .put("success", successCount)
        .put("failures", failureCount)
        .put("successRate", Math.round(successRate * 100) / 100.0)
        .put("uptimeSeconds", Math.round(uptimeSeconds * 100) / 100.0))
      .put("errorBreakdown", new JsonObject()
        .put("validationErrors", validationErrors)
        .put("parseErrors", parseErrors))
      .put("throughput", new JsonObject()
        .put("overall", Math.round(overallThroughput * 100) / 100.0)
        .put("unit", "tx/sec"))
      .put("latency", new JsonObject()
        .put("unit", "microseconds")
        .put("min", latencyStats.getCount() > 0 ? latencyStats.getMin() : 0)
        .put("avg", Math.round(latencyStats.getAverage() * 100) / 100.0)
        .put("max", latencyStats.getCount() > 0 ? latencyStats.getMax() : 0)
        .put("p50", p50)
        .put("p95", p95)
        .put("p99", p99));
  }

  /**
   * Generate HTML dashboard for browser viewing.
   */
  public String toHtmlDashboard() {
    JsonObject data = toJson();
    JsonObject summary = data.getJsonObject("summary");
    JsonObject throughput = data.getJsonObject("throughput");
    JsonObject latency = data.getJsonObject("latency");
    JsonObject errors = data.getJsonObject("errorBreakdown");

    return """
      <!DOCTYPE html>
      <html>
      <head>
        <meta charset="UTF-8">
        <title>Fraud Analytics - Performance Dashboard</title>
        <meta http-equiv="refresh" content="5">
        <style>
          * { margin: 0; padding: 0; box-sizing: border-box; }
          body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; background: linear-gradient(135deg, #1a1a2e 0%%, #16213e 100%%); color: #fff; min-height: 100vh; padding: 20px; }
          .container { max-width: 1200px; margin: 0 auto; }
          h1 { text-align: center; margin-bottom: 10px; font-size: 2.5rem; background: linear-gradient(90deg, #00d4ff, #7b2cbf); -webkit-background-clip: text; -webkit-text-fill-color: transparent; }
          .sla-bar { text-align: center; margin-bottom: 30px; padding: 12px; background: rgba(255,255,255,0.05); border-radius: 8px; font-size: 0.9rem; }
          .sla-item { display: inline-block; margin: 0 20px; }
          .sla-label { color: #888; }
          .sla-value { color: #4ade80; font-weight: bold; }
          .sla-fail { color: #f87171; }
          .sla-pass { color: #4ade80; }
          .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 20px; }
          .card { background: rgba(255,255,255,0.05); border-radius: 16px; padding: 24px; backdrop-filter: blur(10px); border: 1px solid rgba(255,255,255,0.1); }
          .card h2 { font-size: 0.9rem; color: #888; text-transform: uppercase; letter-spacing: 1px; margin-bottom: 16px; }
          .metric { font-size: 2.5rem; font-weight: 700; margin-bottom: 8px; }
          .metric.success { color: #4ade80; }
          .metric.warning { color: #fbbf24; }
          .metric.error { color: #f87171; }
          .metric.info { color: #60a5fa; }
          .sub-metric { font-size: 0.9rem; color: #888; }
          .progress-bar { height: 8px; background: rgba(255,255,255,0.1); border-radius: 4px; overflow: hidden; margin-top: 12px; }
          .progress-fill { height: 100%%; background: linear-gradient(90deg, #4ade80, #22d3ee); transition: width 0.5s ease; }
          .progress-fill.fail { background: linear-gradient(90deg, #f87171, #fbbf24); }
          .latency-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; margin-top: 12px; }
          .latency-item { text-align: center; padding: 12px; background: rgba(255,255,255,0.03); border-radius: 8px; }
          .latency-value { font-size: 1.2rem; font-weight: 600; color: #60a5fa; }
          .latency-label { font-size: 0.75rem; color: #888; margin-top: 4px; }
          .timestamp { text-align: center; margin-top: 30px; color: #666; font-size: 0.85rem; }
        </style>
      </head>
      <body>
        <div class="container">
          <h1>&#128274; Fraud Analytics Performance</h1>
          <div class="sla-bar">
            <span class="sla-item"><span class="sla-label">SLA Target:</span></span>
            <span class="sla-item"><span class="sla-label">Throughput:</span> <span class="sla-value %s">15,000 TPS</span> <span class="sla-label">(actual: %.0f)</span></span>
            <span class="sla-item"><span class="sla-label">Success Rate:</span> <span class="sla-value %s">95%%</span> <span class="sla-label">(actual: %.1f%%)</span></span>
            <span class="sla-item"><span class="sla-label">P99 Latency:</span> <span class="sla-value %s">&lt;1.2s</span> <span class="sla-label">(actual: %.2fms)</span></span>
          </div>
          <div class="grid">
            <div class="card">
              <h2>Total Transactions</h2>
              <div class="metric info">%,d</div>
              <div class="sub-metric">Processed since startup</div>
            </div>
            <div class="card">
              <h2>Success Rate</h2>
              <div class="metric %s">%.1f%%</div>
              <div class="progress-bar"><div class="progress-fill %s" style="width: %.1f%%"></div></div>
              <div class="sub-metric" style="margin-top: 8px">%,d successful / %,d failed</div>
            </div>
            <div class="card">
              <h2>Throughput</h2>
              <div class="metric %s">%.2f</div>
              <div class="sub-metric">transactions per second (target: 15,000)</div>
            </div>
            <div class="card">
              <h2>Uptime</h2>
              <div class="metric" style="color: #a78bfa;">%.0f</div>
              <div class="sub-metric">seconds</div>
            </div>
            <div class="card" style="grid-column: span 2;">
              <h2>Latency Distribution</h2>
              <div class="latency-grid">
                <div class="latency-item"><div class="latency-value">%.2f ms</div><div class="latency-label">MIN</div></div>
                <div class="latency-item"><div class="latency-value">%.2f ms</div><div class="latency-label">AVG</div></div>
                <div class="latency-item"><div class="latency-value">%.2f ms</div><div class="latency-label">MAX</div></div>
                <div class="latency-item"><div class="latency-value">%.2f ms</div><div class="latency-label">P50</div></div>
                <div class="latency-item"><div class="latency-value %s">%.2f ms</div><div class="latency-label">P95</div></div>
                <div class="latency-item"><div class="latency-value %s">%.2f ms</div><div class="latency-label">P99 (target &lt;1200ms)</div></div>
              </div>
            </div>
            <div class="card">
              <h2>Error Breakdown</h2>
              <div class="sub-metric" style="margin-bottom: 8px;">Validation Errors: <span class="metric error" style="font-size: 1.5rem;">%d</span></div>
              <div class="sub-metric">Parse Errors: <span class="metric warning" style="font-size: 1.5rem;">%d</span></div>
            </div>
          </div>
          <div class="timestamp">Auto-refreshes every 5 seconds | Last updated: %s</div>
        </div>
      </body>
      </html>
      """.formatted(
      // SLA status indicators
      throughput.getDouble("overall") >= 15000 ? "sla-pass" : "sla-fail",
      throughput.getDouble("overall"),
      summary.getDouble("successRate") >= 95 ? "sla-pass" : "sla-fail",
      summary.getDouble("successRate"),
      latency.getLong("p99") / 1000.0 <= 1200 ? "sla-pass" : "sla-fail",
      latency.getLong("p99") / 1000.0,
      // Main metrics
      summary.getLong("totalProcessed"),
      summary.getDouble("successRate") >= 95 ? "success" : "error",
      summary.getDouble("successRate"),
      summary.getDouble("successRate") >= 95 ? "" : "fail",
      summary.getDouble("successRate"),
      summary.getLong("success"),
      summary.getLong("failures"),
      throughput.getDouble("overall") >= 15000 ? "success" : "warning",
      throughput.getDouble("overall"),
      summary.getDouble("uptimeSeconds"),
      // Latency in milliseconds (convert from microseconds)
      latency.getDouble("min") / 1000.0,
      latency.getDouble("avg") / 1000.0,
      latency.getDouble("max") / 1000.0,
      latency.getLong("p50") / 1000.0,
      latency.getLong("p95") / 1000.0 <= 1000 ? "" : "warning",
      latency.getLong("p95") / 1000.0,
      latency.getLong("p99") / 1000.0 <= 1200 ? "" : "error",
      latency.getLong("p99") / 1000.0,
      errors.getLong("validationErrors"),
      errors.getLong("parseErrors"),
      java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    );
  }
}







