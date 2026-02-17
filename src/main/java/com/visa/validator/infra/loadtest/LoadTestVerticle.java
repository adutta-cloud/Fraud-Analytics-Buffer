package com.visa.validator.infra.loadtest;

import com.google.common.util.concurrent.RateLimiter;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.json.JsonObject;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance load test generator using native Kafka producer.
 * Uses Guava RateLimiter to control overall transaction rate.
 */
public class LoadTestVerticle extends AbstractVerticle {

  private static final Logger log = LoggerFactory.getLogger(LoadTestVerticle.class);

  private final int targetTps;
  private final int durationSeconds;
  private final String[] currencies = {"USD", "EUR", "GBP", "JPY", "INR"};

  private static final int PRODUCER_THREADS = 16;
  private static final String TOPIC = "visa-transactions";

  private final AtomicLong messagesSent = new AtomicLong(0);
  private final AtomicBoolean running = new AtomicBoolean(false);
  private ExecutorService executorService;

  public LoadTestVerticle(int targetTps, int durationSeconds) {
    this.targetTps = targetTps;
    this.durationSeconds = durationSeconds;
  }

  @Override
  public void start() {
    log.info("╔══════════════════════════════════════════════════════════════╗");
    log.info("║                    LOAD TEST STARTING                        ║");
    log.info("╠══════════════════════════════════════════════════════════════╣");
    log.info("║  Target TPS:       {}                                   ║", String.format("%8d", targetTps));
    log.info("║  Duration:         {} seconds                           ║", String.format("%8d", durationSeconds));
    log.info("║  Producer Threads: {}                                       ║", String.format("%8d", PRODUCER_THREADS));
    log.info("║  Expected Total:   {} transactions                  ║", String.format("%12d", (long) targetTps * durationSeconds));
    log.info("╚══════════════════════════════════════════════════════════════╝");

    running.set(true);
    executorService = Executors.newFixedThreadPool(PRODUCER_THREADS);
    RateLimiter rateLimiter = RateLimiter.create(targetTps);
    long endTime = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(durationSeconds);

    // Launch parallel producer threads using native Kafka producer
    for (int i = 0; i < PRODUCER_THREADS; i++) {
      final int threadId = i;
      executorService.submit(() -> runProducer(threadId, rateLimiter, endTime));
    }

    // Schedule completion log
    vertx.setTimer(TimeUnit.SECONDS.toMillis(durationSeconds) + 3000, id -> {
      running.set(false);
      executorService.shutdown();
      double actualTps = messagesSent.get() / (double) durationSeconds;
      log.info("╔══════════════════════════════════════════════════════════════╗");
      log.info("║                   LOAD TEST COMPLETED                        ║");
      log.info("╠══════════════════════════════════════════════════════════════╣");
      log.info("║  Total Sent:       {} transactions                  ║", String.format("%12d", messagesSent.get()));
      log.info("║  Actual TPS:       {}                                 ║", String.format("%12.2f", actualTps));
      log.info("║  Efficiency:       {}%%                                 ║", String.format("%12.1f", (actualTps / targetTps) * 100));
      log.info("╚══════════════════════════════════════════════════════════════╝");
    });
  }

  private void runProducer(int threadId, RateLimiter rateLimiter, long endTime) {
    // Use native Kafka producer (not Vert.x wrapped) for thread safety
    Properties props = new Properties();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.ACKS_CONFIG, "0");
    props.put(ProducerConfig.LINGER_MS_CONFIG, "5");
    props.put(ProducerConfig.BATCH_SIZE_CONFIG, "65536");
    props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, "67108864");

    try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
      log.info("Producer thread {} started", threadId);

      while (running.get() && System.currentTimeMillis() < endTime) {
        rateLimiter.acquire();
        if (!running.get()) break;

        JsonObject tx = generateTransaction();
        ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, tx.getString("id"), tx.encode());

        producer.send(record, (metadata, exception) -> {
          if (exception != null) {
            log.error("Failed to send message: {}", exception.getMessage());
          }
        });

        long sent = messagesSent.incrementAndGet();
        if (sent % 10000 == 0) {
          log.info("Progress: {} transactions sent...", sent);
        }
      }

      producer.flush();
      log.info("Producer thread {} stopped, sent messages", threadId);
    } catch (Exception e) {
      log.error("Producer thread {} error: {}", threadId, e.getMessage(), e);
    }
  }

  private JsonObject generateTransaction() {
    return new JsonObject()
      .put("id", UUID.randomUUID().toString())
      .put("senderId", UUID.randomUUID().toString())
      .put("merchantId", UUID.randomUUID().toString())
      .put("amount", 10 + Math.random() * 90)
      .put("currency", currencies[(int) (Math.random() * currencies.length)]);
  }

  @Override
  public void stop() {
    running.set(false);
    if (executorService != null) {
      executorService.shutdownNow();
    }
  }
}
