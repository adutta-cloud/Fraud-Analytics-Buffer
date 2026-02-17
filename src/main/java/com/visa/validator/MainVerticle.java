package com.visa.validator;

import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.visa.validator.api.rest.MetricsHandler;
import com.visa.validator.core.port.in.TransactionValidatorPort;
import com.visa.validator.core.port.out.VelocityRepositoryPort;
import com.visa.validator.core.service.GuavaValidatorService;
import com.visa.validator.infra.cache.HazelcastVelocityRepository;
import com.visa.validator.infra.messaging.TransactionConsumerVerticle;
import com.visa.validator.infra.metrics.PerformanceMetrics;
import com.visa.validator.infra.loadtest.LoadTestVerticle;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Promise;
import io.vertx.core.Verticle;
import io.vertx.ext.web.Router;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.function.Supplier;

public class MainVerticle extends AbstractVerticle {

  private static final Logger log = LoggerFactory.getLogger(MainVerticle.class);
  private static final int HTTP_PORT = 8080;

  // Shared metrics instance
  private final PerformanceMetrics performanceMetrics = new PerformanceMetrics();

  @Override
  public void start(Promise<Void> startPromise) throws Exception {
    log.info("Starting Visa Validator Service Bootstrap...");

    try {
      // Setup Prometheus registry for Grafana integration
      PrometheusMeterRegistry prometheusRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
      performanceMetrics.registerWithMicrometer(prometheusRegistry);

      HazelcastInstance hazelcastInstance = Hazelcast.newHazelcastInstance();
      VelocityRepositoryPort velocityRepo = new HazelcastVelocityRepository(hazelcastInstance);

      TransactionValidatorPort validatorService = new GuavaValidatorService(velocityRepo);

      // Create handlers
      MetricsHandler metricsHandler = new MetricsHandler(performanceMetrics);

      // Setup HTTP server with metrics endpoints
      Router router = Router.router(vertx);

      // Metrics endpoints for managers
      router.get("/metrics").handler(metricsHandler::handleMetricsJson);
      router.get("/dashboard").handler(metricsHandler::handleDashboard);
      router.post("/metrics/reset").handler(metricsHandler::handleReset);

      // Prometheus endpoint for Grafana
      router.get("/prometheus").handler(ctx -> {
        ctx.response()
          .putHeader("Content-Type", "text/plain")
          .end(prometheusRegistry.scrape());
      });

      // Health check
      router.get("/health").handler(ctx -> {
        ctx.response()
          .putHeader("Content-Type", "application/json")
          .end("{\"status\": \"UP\"}");
      });

      // Load test endpoint - POST /loadtest?tps=100&duration=30
      router.post("/loadtest").handler(ctx -> {
        int tps = Integer.parseInt(ctx.request().getParam("tps", "100"));
        int duration = Integer.parseInt(ctx.request().getParam("duration", "30"));

        vertx.deployVerticle(new LoadTestVerticle(tps, duration));

        ctx.response()
          .putHeader("Content-Type", "application/json")
          .end(new io.vertx.core.json.JsonObject()
            .put("status", "Load test started")
            .put("targetTps", tps)
            .put("durationSeconds", duration)
            .encode());
      });

      vertx.createHttpServer()
        .requestHandler(router)
        .listen(HTTP_PORT)
        .onSuccess(server -> log.info("HTTP server started on port {}", HTTP_PORT))
        .onFailure(err -> log.error("Failed to start HTTP server", err));

      // Deploy Kafka consumers with shared metrics
      Supplier<Verticle> verticleSupplier = () -> new TransactionConsumerVerticle(validatorService, performanceMetrics);

      // Use more consumer instances for higher throughput (at least 8, or 2x CPU cores)
      int consumerInstances = Math.max(8, Runtime.getRuntime().availableProcessors() * 2);
      DeploymentOptions options = new DeploymentOptions()
        .setInstances(consumerInstances);

      log.info("Deploying {} consumer verticle instances", consumerInstances);

      vertx.deployVerticle(verticleSupplier, options).onComplete(ar -> {
        if (ar.succeeded()) {
          log.info("═══════════════════════════════════════════════════════════════");
          log.info("  Visa Validator System is ONLINE");
          log.info("═══════════════════════════════════════════════════════════════");
          log.info("  📊 Dashboard:   http://localhost:{}/dashboard", HTTP_PORT);
          log.info("  📈 Metrics API: http://localhost:{}/metrics", HTTP_PORT);
          log.info("  🔥 Prometheus:  http://localhost:{}/prometheus", HTTP_PORT);
          log.info("  💚 Health:      http://localhost:{}/health", HTTP_PORT);
          log.info("  🚀 Load Test:   POST http://localhost:{}/loadtest?tps=100&duration=30", HTTP_PORT);
          log.info("═══════════════════════════════════════════════════════════════");
          startPromise.complete();
        } else {
          log.error("Failed to deploy Kafka Consumer", ar.cause());
          startPromise.fail(ar.cause());
        }
      });
    }
    catch (Exception e) {
      startPromise.fail(e);
    }
  }
}
