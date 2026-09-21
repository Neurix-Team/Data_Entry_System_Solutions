package com.dataentry.repository;

import com.dataentry.model.Announcement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    /**
     * Broadcast history, newest first. The entity's teamFilter scopes reads to the
     * caller's team automatically (super admins see every team).
     */
    List<Announcement> findTop20ByOrderByCreatedAtDesc();
}