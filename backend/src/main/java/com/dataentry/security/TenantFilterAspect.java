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

@Aspect
@Component
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
