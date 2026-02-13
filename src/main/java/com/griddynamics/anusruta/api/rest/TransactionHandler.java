package com.griddynamics.anusruta.api.rest;

import com.griddynamics.anusruta.core.model.Transaction;
import com.griddynamics.anusruta.core.port.in.TransactionValidatorPort;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

public class TransactionHandler {

  private final TransactionValidatorPort validatorPort;

  public TransactionHandler(TransactionValidatorPort validatorPort) {
    this.validatorPort = validatorPort;
  }

  public void  handleValidation(RoutingContext ctx) {
    try {
      JsonObject body = ctx.body().asJsonObject();
      Transaction transaction = body.mapTo(Transaction.class);

      validatorPort.validate(transaction);

      ctx.response()
        .setStatusCode(200)
        .putHeader("Content-Type", "application/json")
        .end(new JsonObject().put("status", "APPROVED").encode());
    }
    catch (IllegalArgumentException | NullPointerException e) {
      ctx.response()
        .setStatusCode(400)
        .end(new JsonObject().put("error", e.getMessage()).encode());
    }
    catch (Exception e) {
      ctx.response()
        .setStatusCode(500)
        .end();
    }
  }
}
