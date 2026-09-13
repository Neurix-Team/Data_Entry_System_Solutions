package com.dataentry.repository;

import com.dataentry.model.UploadSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface UploadSessionRepository extends JpaRepository<UploadSession, String> {
    long countByOwnerId(Long ownerId);
    List<UploadSession> findAllByExpiresAtBefore(Instant cutoff);
}
