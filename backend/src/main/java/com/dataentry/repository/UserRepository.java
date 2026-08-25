package com.dataentry.repository;

import com.dataentry.model.Role;
import com.dataentry.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);

    /** Global cross-tenant role lookup. Used by SUPER_ADMIN endpoints. */
    List<User> findAllByRole(Role role);

    /** Every user attached to a specific team. Ordered newest-first for display. */
    List<User> findAllByTeamIdOrderByCreatedAtDesc(Long teamId);
}
