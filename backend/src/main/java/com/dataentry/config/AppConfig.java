package com.dataentry.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.time.Clock;
import java.util.List;
import java.util.Locale;

/**
 * {@link EnableTransactionManagement} is normally installed by Spring Boot at
 * {@code LOWEST_PRECEDENCE} — the {@code TransactionInterceptor} then runs innermost. That
 * left no room for the {@code TenantFilterAspect} to sit INSIDE the transaction, so its
 * {@code enableFilter} call landed on a temporary session that got discarded before the
 * real query ran. Setting the order below leaves headroom for tenant-scoping advice to
 * run one step inner, guaranteeing the filter is enabled on the session that actually
 * executes the JPQL.
 */
@Configuration
@EnableTransactionManagement(order = Ordered.LOWEST_PRECEDENCE - 100)
public class AppConfig {

    /**
     * Inject Clock everywhere instead of calling LocalDate.now() / Instant.now() directly.
     * Tests can then swap it for Clock.fixed(...) to make time-based logic deterministic.
     */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    /**
     * Resolves the request Locale from the Accept-Language header. The Localizer service reads
     * this via LocaleContextHolder to decide whether to return name_en or name_ar.
     */
    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setDefaultLocale(Locale.ENGLISH);
        resolver.setSupportedLocales(List.of(Locale.ENGLISH, Locale.forLanguageTag("ar")));
        return resolver;
    }
}
