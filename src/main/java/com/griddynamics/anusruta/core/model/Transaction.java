package com.griddynamics.anusruta.core.model;

import java.time.LocalDateTime;
import java.util.Currency;
import java.util.UUID;

public record Transaction(
  UUID id,
  UUID senderId,
  UUID receiverId,
  Double amount,
  Currency currency,
  LocalDateTime timestamp
) {
}
