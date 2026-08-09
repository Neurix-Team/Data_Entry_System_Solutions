package com.dataentry.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.time.Clock;
import java.util.List;
import java.util.Locale;

@Configuration
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
