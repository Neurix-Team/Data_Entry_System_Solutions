package com.dataentry.repository;

import com.dataentry.model.Team;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TeamRepository extends JpaRepository<Team, Long> {
    Optional<Team> findBySlug(String slug);
    boolean existsBySlugIgnoreCase(String slug);
    List<Team> findAllByOrderByCreatedAtAsc();
}
