package com.griddynamics.anusruta.core.port.in;

import com.griddynamics.anusruta.core.model.Transaction;

public interface TransactionValidatorPort {
  void validate(Transaction transaction);
}
