package com.dataentry.service;

import com.dataentry.model.UploadUsage;
import com.dataentry.repository.UploadUsageRepository;
import com.dataentry.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;

/** Persistent upload-attempt budget. Aborted sessions do not refund the budget. */
@Service
public class UploadQuotaService {
    private final long dailyBytes;
    private final UploadUsageRepository usage;
    private final UserRepository users;

    public UploadQuotaService(@Value("${app.uploads.per-user-daily-bytes:524288000}") long dailyBytes,
                              UploadUsageRepository usage, UserRepository users) {
        this.dailyBytes = dailyBytes;
        this.usage = usage;
        this.users = users;
    }

    @Transactional
    public void chargeOrThrow(Long userId, long bytes) {
        if (userId == null || bytes <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        // Serialize all reservations for this user, including first-row creation, across instances.
        users.lockForUploadBudget(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        Instant now = Instant.now();
        UploadUsage record = usage.findById(userId).orElseGet(() -> {
            UploadUsage fresh = new UploadUsage();
            fresh.setUserId(userId);
            fresh.setWindowStartedAt(now);
            return fresh;
        });
        if (!record.getWindowStartedAt().plusSeconds(86400).isAfter(now)) {
            record.setWindowStartedAt(now);
            record.setChargedBytes(0);
        }
        if (bytes > dailyBytes - record.getChargedBytes()) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Daily upload budget exceeded. Aborted uploads also count toward this budget.");
        }
        record.setChargedBytes(record.getChargedBytes() + bytes);
        usage.save(record);
    }
}
