package com.griddynamics.anusruta.core.service;

import com.google.common.util.concurrent.RateLimiter;
import com.griddynamics.anusruta.core.model.Transaction;
import com.griddynamics.anusruta.core.port.in.TransactionValidatorPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

public class GuavaValidatorService implements TransactionValidatorPort {

  private static final Logger log = LoggerFactory.getLogger(GuavaValidatorService.class);

  private final RateLimiter globalRateLimiter = RateLimiter.create(10000); // Example: 1000 transactions per second

  @Override
  public void validate(Transaction transaction) {
    checkArgument(globalRateLimiter.tryAcquire(), "Server is currently overloaded. Please try again later.");
    checkNotNull(transaction, "transaction can not be null");
    checkArgument(transaction.currency().getCurrencyCode().length() == 3, "Invalid ISO Currency Code");
  }
}
