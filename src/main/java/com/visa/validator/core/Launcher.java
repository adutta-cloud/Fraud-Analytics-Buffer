package com.visa.validator.core;

import com.visa.validator.MainVerticle;
import io.vertx.core.Vertx;

public class Launcher {
  public static void main(String[] args) {
    MainVerticle mainVerticle = new MainVerticle();
    Vertx vertx = Vertx.vertx();
    vertx.deployVerticle(mainVerticle);
  }
}
