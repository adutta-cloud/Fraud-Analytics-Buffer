package com.visa.validator.core.port.in;

import com.visa.validator.core.model.Transaction;

public interface TransactionValidatorPort {
  void validate(Transaction transaction);
}
