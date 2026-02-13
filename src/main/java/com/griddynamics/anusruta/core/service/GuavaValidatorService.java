package com.griddynamics.anusruta.core.service;

import com.google.common.util.concurrent.RateLimiter;
import com.griddynamics.anusruta.core.model.Transaction;
import com.griddynamics.anusruta.core.port.in.TransactionValidatorPort;
import com.griddynamics.anusruta.core.port.out.VelocityRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

public class GuavaValidatorService implements TransactionValidatorPort {

  private static final Logger log = LoggerFactory.getLogger(GuavaValidatorService.class);

  private final VelocityRepositoryPort velocityRepositoryPort;
  private static final Double DAILY_LIMIT = 10000.0;

  private final RateLimiter globalRateLimiter = RateLimiter.create(10000); // 1000 transactions per second

  public GuavaValidatorService(VelocityRepositoryPort velocityRepositoryPort) {
    this.velocityRepositoryPort = velocityRepositoryPort;
  }



  @Override
  public void validate(Transaction transaction) {
    checkArgument(globalRateLimiter.tryAcquire(),
      "Server is currently overloaded. Please try again later.");

    checkNotNull(transaction,
      "transaction can not be null");
    checkArgument(transaction.amount() > 0,
      "Amount must be positive");
    checkArgument(transaction.senderId() != null,
      "Sender ID cannot be null");
    checkArgument(transaction.receiverId() != null,
      "Receiver ID cannot be null");
    checkArgument(!transaction.senderId().equals(transaction.receiverId()),
      "Sender and receiver cannot be the same");
    checkArgument(transaction.currency().getCurrencyCode().length() == 3,
      "Invalid ISO Currency Code");

    Double currentTotal = velocityRepositoryPort.getAccumulatedAmount(transaction.senderId());

    log.info("Checking velocity for sender: {}. Current total: {}", transaction.senderId(), currentTotal);

    checkArgument((currentTotal + transaction.amount()) <= DAILY_LIMIT,
      "Daily transaction limit exceeded for sender: %s. Max: %s, Current: %s"
        + transaction.senderId(), DAILY_LIMIT, currentTotal);

    velocityRepositoryPort.incrementAmount(transaction.senderId(), transaction.amount());
  }
}
