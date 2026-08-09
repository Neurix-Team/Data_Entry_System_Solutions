package com.dataentry.repository;

import com.dataentry.model.LoginAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface LoginAttemptRepository extends JpaRepository<LoginAttempt, Long> {

    long countByAttemptKeyAndAttemptedAtGreaterThanEqual(String key, Instant since);

    @Modifying
    @Query("delete from LoginAttempt a where a.attemptKey = :key")
    void deleteByKey(@Param("key") String key);

    @Modifying
    @Query("delete from LoginAttempt a where a.attemptedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
