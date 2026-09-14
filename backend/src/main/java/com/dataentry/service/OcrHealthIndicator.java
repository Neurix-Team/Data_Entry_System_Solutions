package com.dataentry.service;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/**
 * "ocr" component on /actuator/health. Reports DEGRADED (a custom status that does not
 * pull the overall health to DOWN) when Tesseract or its language files are missing, with
 * the reason in the details, so the state is visible without reading the boot log.
 */
@Component("ocr")
public class OcrHealthIndicator implements HealthIndicator {

    static final Status DEGRADED = new Status("DEGRADED", "OCR is not available");

    private final OcrRuntime runtime;

    public OcrHealthIndicator(OcrRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Health health() {
        OcrRuntime.Status s = runtime.status();
        Health.Builder b = s.ready() ? Health.up() : Health.status(DEGRADED);
        b.withDetail("engineLoaded", s.engineLoaded());
        if (s.engineVersion() != null) b.withDetail("engineVersion", s.engineVersion());
        b.withDetail("datapath", s.datapath() == null ? "not found" : s.datapath());
        b.withDetail("languages", String.join("+", s.languages()));
        if (!s.missingLanguages().isEmpty()) b.withDetail("missingLanguages", s.missingLanguages());
        if (s.error() != null) b.withDetail("error", s.error());
        return b.build();
    }
}
