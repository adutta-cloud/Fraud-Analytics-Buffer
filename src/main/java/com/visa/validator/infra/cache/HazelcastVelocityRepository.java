package com.visa.validator.infra.cache;

import com.visa.validator.core.port.out.VelocityRepositoryPort;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class HazelcastVelocityRepository implements VelocityRepositoryPort {

  private static final Logger log = LoggerFactory.getLogger(HazelcastVelocityRepository.class);

  private final IMap<UUID, Double> velocityMap;

  // Local cache for faster reads (eventual consistency is OK for velocity checks)
  private final ConcurrentHashMap<UUID, Double> localCache = new ConcurrentHashMap<>();

  public HazelcastVelocityRepository(HazelcastInstance hazelcastInstance) {
    this.velocityMap = hazelcastInstance.getMap("velocity-limits");
  }

  @Override
  public Double getAccumulatedAmount(UUID senderId) {
    // Check local cache first for speed
    return localCache.getOrDefault(senderId, velocityMap.getOrDefault(senderId, 0.0));
  }

  @Override
  public void incrementAmount(UUID senderId, Double amount) {
    // Update local cache immediately
    localCache.merge(senderId, amount, Double::sum);

    // Async update to Hazelcast (non-blocking for performance)
    velocityMap.setAsync(senderId, localCache.get(senderId));

    log.trace("Incremented velocity for sender: {} by {}", senderId, amount);
  }
}
