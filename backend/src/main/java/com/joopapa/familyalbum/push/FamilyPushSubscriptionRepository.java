package com.joopapa.familyalbum.push;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FamilyPushSubscriptionRepository extends JpaRepository<FamilyPushSubscription, UUID> {
    Optional<FamilyPushSubscription> findByEndpoint(String endpoint);

    List<FamilyPushSubscription> findByActiveTrue();
}