package com.griddynamics.anusruta.infra.cache;

import com.griddynamics.anusruta.core.port.out.VelocityRepositoryPort;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

public class HazelcastVelocityRepository implements VelocityRepositoryPort {

  private static final Logger log = LoggerFactory.getLogger(HazelcastVelocityRepository.class);

  private final IMap<UUID, Double> velocityMap;

  public HazelcastVelocityRepository(HazelcastInstance hazelcastInstance) {
    this.velocityMap = hazelcastInstance.getMap("velocity-limits");
  }

  @Override
  public Double getAccumulatedAmount(UUID senderId) {
    return velocityMap.getOrDefault(senderId, 0.0);
  }

  @Override
  public void incrementAmount(UUID senderId, Double amount) {
    velocityMap.executeOnKey(senderId, entry ->  {
      Double current = entry.getValue();
      if (current == null) {
        current = 0.0;
      }
      entry.setValue(current + amount);
      return null;
    });

    log.debug("Incremented Hazelcast state for sender: {} by {}", senderId, amount);
  }
}
