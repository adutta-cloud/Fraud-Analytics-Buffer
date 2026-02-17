package com.visa.validator.api.rest;

import com.visa.validator.infra.metrics.PerformanceMetrics;
import io.vertx.ext.web.RoutingContext;

/**
 * REST handler for exposing performance metrics.
 * Provides JSON API and HTML dashboard endpoints.
 */
public class MetricsHandler {

  private final PerformanceMetrics metrics;

  public MetricsHandler(PerformanceMetrics metrics) {
    this.metrics = metrics;
  }

  /**
   * GET /metrics - Returns JSON metrics
   */
  public void handleMetricsJson(RoutingContext ctx) {
    ctx.response()
      .setStatusCode(200)
      .putHeader("Content-Type", "application/json")
      .putHeader("Access-Control-Allow-Origin", "*")
      .end(metrics.toJson().encodePrettily());
  }

  /**
   * GET /dashboard - Returns HTML dashboard for managers
   */
  public void handleDashboard(RoutingContext ctx) {
    ctx.response()
      .setStatusCode(200)
      .putHeader("Content-Type", "text/html; charset=UTF-8")
      .end(metrics.toHtmlDashboard());
  }

  /**
   * POST /metrics/reset - Resets all metrics
   */
  public void handleReset(RoutingContext ctx) {
    metrics.reset();
    ctx.response()
      .setStatusCode(200)
      .putHeader("Content-Type", "application/json")
      .end("{\"status\": \"Metrics reset successfully\"}");
  }
}


