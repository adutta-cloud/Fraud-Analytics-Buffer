package com.visa.validator.infra.messaging;

import com.google.common.base.Stopwatch;
import com.visa.validator.core.model.Transaction;
import com.visa.validator.core.port.in.TransactionValidatorPort;
import com.visa.validator.infra.metrics.PerformanceMetrics;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.json.JsonObject;
import io.vertx.kafka.client.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.LocalDateTime;
import java.util.Currency;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class TransactionConsumerVerticle extends AbstractVerticle {

  private static final Logger log = LoggerFactory.getLogger(TransactionConsumerVerticle.class);
  private final TransactionValidatorPort validatorPort;
  private final PerformanceMetrics metrics;

  // Configuration for metrics reporting interval (in seconds)
  private static final long METRICS_REPORT_INTERVAL_SEC = 10;

  public TransactionConsumerVerticle(TransactionValidatorPort validatorPort, PerformanceMetrics metrics) {
    this.validatorPort = validatorPort;
    this.metrics = metrics;
  }

  @Override
  public void start() {
    Map<String, String> config = new HashMap<>();
    config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
    config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
    config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
    config.put(ConsumerConfig.GROUP_ID_CONFIG, "validator-service-group");
    config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    // Performance tuning for high throughput
    config.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, "1048576");  // 1MB min fetch
    config.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, "100");    // Max wait 100ms
    config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "5000");    // 5000 records per poll

    KafkaConsumer<String, String> consumer = KafkaConsumer.create(vertx, config);

    // Schedule periodic metrics reporting
    vertx.setPeriodic(TimeUnit.SECONDS.toMillis(METRICS_REPORT_INTERVAL_SEC), id -> {
      metrics.logStats();
    });

    consumer.handler(record -> {
      // Start timing with Guava Stopwatch
      Stopwatch stopwatch = metrics.startTimer();

      try {
        // 1. Attempt to parse JSON
        JsonObject json = new JsonObject(record.value());

        // 2. Process normally
        MDC.put("correlationId", json.getString("id", "unknown"));
        Transaction tx = mapToRecord(json);
        validatorPort.validate(tx);

        // Record success with latency
        long latencyMicros = metrics.elapsed(stopwatch);
        metrics.recordSuccess(latencyMicros);

        log.debug("Validated transaction: {} in {}μs", tx.id(), latencyMicros);

      } catch (io.vertx.core.json.DecodeException e) {
        // This handles the random garbage from the perf-test
        metrics.recordFailure(PerformanceMetrics.PARSE_ERROR);
        log.warn("Received non-JSON garbage from Kafka. Skipping message.");
      } catch (IllegalArgumentException e) {
        // Validation errors (from Guava Preconditions)
        metrics.recordFailure(PerformanceMetrics.VALIDATION_ERROR);
        log.warn("Validation failed: {}", e.getMessage());
      } catch (Exception e) {
        metrics.recordFailure(PerformanceMetrics.FAILURE);
        log.error("Unexpected error processing transaction: {}", e.getMessage());
      } finally {
        MDC.clear();
      }
    });

    consumer.subscribe("visa-transactions");
    log.info("Transaction consumer started. Metrics will be reported every {} seconds.", METRICS_REPORT_INTERVAL_SEC);
  }

  private Transaction mapToRecord(JsonObject json) {
    return new Transaction(
      UUID.fromString(json.getString("id")),
      UUID.fromString(json.getString("senderId")),
      UUID.fromString(json.getString("merchantId")),
      json.getDouble("amount"),
      Currency.getInstance(json.getString("currency")),
      LocalDateTime.now()
    );
  }
}
