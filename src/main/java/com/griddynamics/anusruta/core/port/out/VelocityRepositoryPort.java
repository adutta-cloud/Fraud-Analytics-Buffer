package com.griddynamics.anusruta.core.port.out;

import java.util.UUID;

public interface VelocityRepositoryPort {

  Double getAccumulatedAmount(UUID senderId);
  void incrementAmount(UUID senderId, Double amount);
}
