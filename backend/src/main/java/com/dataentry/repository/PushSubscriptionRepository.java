package com.dataentry.repository;

import com.dataentry.model.PushSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {

    /** Every browser this user opted in from — the fan-out list for a notification. */
    List<PushSubscription> findByUserId(Long userId);

    /** Upsert lookup: the endpoint identifies the browser, not the account. */
    Optional<PushSubscription> findByEndpoint(String endpoint);

    /** Unsubscribe: only the owner may drop their own subscription. */
    @Modifying
    @Query("delete from PushSubscription s where s.endpoint = :endpoint and s.user.id = :userId")
    int deleteByEndpointAndUserId(@Param("endpoint") String endpoint, @Param("userId") Long userId);
}