package com.dataentry.service;

import com.dataentry.model.User;
import com.dataentry.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Records failed second-factor attempts in its own committed transaction, so the counter
 * (and the lockout it trips) survives even when the surrounding request ends in a 401/429
 * that rolls the caller's transaction back. Without this, every wrong code would look like
 * the first wrong code.
 */
@Service
public class MfaFailureRecorder {

    private static final Logger log = LoggerFactory.getLogger(MfaFailureRecorder.class);
    static final int MAX_FAILED_ATTEMPTS = 5;
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private final UserRepository userRepository;
    private final AuditService audit;

    public MfaFailureRecorder(UserRepository userRepository, AuditService audit) {
        this.userRepository = userRepository;
        this.audit = audit;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void register(User user) {
        int attempts = user.getMfaFailedAttempts() + 1;
        user.setMfaFailedAttempts(attempts);
        if (attempts >= MAX_FAILED_ATTEMPTS) {
            user.setMfaLockedUntil(Instant.now().plus(LOCK_DURATION));
            log.warn("MFA challenge locked for user '{}' (id={}) after {} failed attempts.",
                    user.getUsername(), user.getId(), attempts);
            audit.record(AuditService.Action.MFA_CHALLENGE_FAILED, AuditService.EntityType.USER,
                    user.getId(), "mfaLocked=true attempts=" + attempts);
        }
        userRepository.save(user);
    }
}
