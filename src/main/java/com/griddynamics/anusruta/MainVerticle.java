package com.griddynamics.anusruta;

import com.griddynamics.anusruta.api.rest.TransactionHandler;
import com.griddynamics.anusruta.core.port.in.TransactionValidatorPort;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.VerticleBase;
import io.vertx.ext.web.Router;
import org.slf4j.MDC;

import java.util.UUID;

public class MainVerticle extends AbstractVerticle {

  private TransactionValidatorPort transactionValidatorPort;
  private TransactionHandler transactionHandler;

  @Override
  public void start(Promise<Void> startPromise) throws Exception {
    Router router = Router.router(vertx);

    router.route().handler(ctx -> {
      String correlationId = UUID.randomUUID().toString();
      MDC.put("correlationId", correlationId);
      ctx.response().putHeader("X-Correlation-Id", correlationId);
      ctx.next();
      ctx.response().endHandler(v -> MDC.clear());
    });

    router.post("/").handler(ctx -> {})

    vertx.createHttpServer().requestHandler(req -> {
      req.response()
        .putHeader("content-type", "text/plain")
        .end("Hello from Vert.x!");
    }).listen(8888).onSuccess(http -> {
      System.out.println("HTTP server started on port 8888");
    });
  }
}
