package com.dataentry.security;

import jakarta.persistence.EntityManager;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Enables Hibernate's {@code teamFilter} on the current session for the duration of every
 * {@code @Transactional} call in the service and controller layers.
 *
 * <p>Rules:
 * <ul>
 *   <li>SUPER_ADMIN → filter stays off; they see and write across every team.</li>
 *   <li>Non-super role with a team id in {@link TenantContext} → filter enabled with that id.</li>
 *   <li>No auth context (e.g. login endpoint, {@code DataSeeder} at startup) → filter stays off,
 *       so bootstrap paths can find any user regardless of tenant.</li>
 * </ul>
 *
 * <p>Order is high (runs before other aspects) so the filter is enabled before any repository
 * work inside the transaction. {@code disableFilter} in the finally block prevents the setting
 * from leaking into a nested caller with a different tenant context.
 */
@Aspect
@Component
// Runs INSIDE Spring's @Transactional interceptor. AppConfig moves TransactionInterceptor to
// LOWEST_PRECEDENCE - 100 so this aspect (LOWEST_PRECEDENCE - 50) is one step inner — the
// transaction has already opened its Hibernate session by the time we unwrap, so the
// enableFilter below lands on the session that actually runs the JPQL. With the default
// tx order (LOWEST_PRECEDENCE) the filter would attach to a throwaway session and the
// real query would run unfiltered.
@Order(Ordered.LOWEST_PRECEDENCE - 50)
public class TenantFilterAspect {

    private static final Logger log = LoggerFactory.getLogger(TenantFilterAspect.class);

    private final EntityManager entityManager;

    public TenantFilterAspect(EntityManager entityManager) {
        this.entityManager = entityManager;
        log.info("TenantFilterAspect bean created (order={})", Ordered.LOWEST_PRECEDENCE - 1);
    }

    @Around("@within(org.springframework.transaction.annotation.Transactional) "
            + "|| @annotation(org.springframework.transaction.annotation.Transactional)")
    public Object applyTenantFilter(ProceedingJoinPoint pjp) throws Throwable {
        // The aspect fires on ~50+ @Transactional methods across the codebase. A single
        // request can hit it 3-5 times as controllers → services → nested tx methods
        // cascade. Guarding at trace level avoids the String.format + log I/O on the
        // hot path; enable com.dataentry.security=TRACE only when diagnosing tenant issues.
        if (log.isTraceEnabled()) {
            log.trace("aspect fired for {} (teamId={}, super={})",
                    pjp.getSignature().toShortString(),
                    TenantContext.getTeamId(),
                    TenantContext.isSuperAdmin());
        }
        if (TenantContext.isSuperAdmin()) {
            return pjp.proceed();
        }
        Long teamId = TenantContext.getTeamId();
        if (teamId == null) {
            return pjp.proceed();
        }
        Session session = entityManager.unwrap(Session.class);
        boolean wasEnabled = session.getEnabledFilter("teamFilter") != null;
        session.enableFilter("teamFilter").setParameter("teamId", teamId);
        try {
            return pjp.proceed();
        } finally {
            if (!wasEnabled) {
                session.disableFilter("teamFilter");
            }
        }
    }
}
