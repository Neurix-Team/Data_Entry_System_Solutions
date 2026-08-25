package com.dataentry.service;

import com.dataentry.dto.ApiTokenDtos;
import com.dataentry.model.ApiToken;
import com.dataentry.model.User;
import com.dataentry.repository.ApiTokenRepository;
import com.dataentry.repository.UserRepository;
import com.dataentry.security.ApiTokenAuthFilter;
import com.dataentry.security.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Personal-access token lifecycle. Only reachable by SUPER_ADMIN via
 * {@code /api/super/api-tokens} — see {@link com.dataentry.controller.ApiTokenAdminController}.
 *
 * <p>The plaintext secret exists only inside {@link #create}. Callers get one chance to
 * copy it; the DB stores only the SHA-256 hash.
 */
@Service
@Transactional(readOnly = true)
public class ApiTokenService {

    /**
     * Byte length of the random part of a token. 32 bytes = 256 bits, base64url-encodes to
     * 43 characters. With the {@code nrx_} prefix the final plaintext is ~47 chars.
     */
    private static final int RANDOM_BYTES = 32;

    private final ApiTokenRepository repository;
    private final UserRepository userRepository;
    private final SecureRandom random = new SecureRandom();

    public ApiTokenService(ApiTokenRepository repository, UserRepository userRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
    }

    public List<ApiTokenDtos.Row> list() {
        List<ApiToken> all = repository.findAllByOrderByCreatedAtDesc();
        // Prefetch creator usernames in one query rather than N lookups.
        Map<Long, String> creatorNames = usernamesByIdFor(all);
        Instant now = Instant.now();
        return all.stream().map(t -> toRow(t, creatorNames, now)).toList();
    }

    @Transactional
    public ApiTokenDtos.CreateResponse create(ApiTokenDtos.CreateRequest req) {
        String plaintext = mintPlaintext();
        String hash = ApiTokenAuthFilter.sha256Hex(plaintext);
        String prefix = plaintext.substring(0, 12); // "nrx_" + first 8 secret chars

        Instant expiresAt = null;
        if (req.expiresInDays() != null && req.expiresInDays() > 0) {
            expiresAt = Instant.now().plus(req.expiresInDays(), ChronoUnit.DAYS);
        }

        ApiToken saved = repository.save(ApiToken.builder()
                .name(req.name().trim())
                .tokenHash(hash)
                .prefix(prefix)
                .createdByUserId(TenantContext.getUserId())
                .createdAt(Instant.now())
                .expiresAt(expiresAt)
                .build());

        Map<Long, String> creators = usernamesByIdFor(List.of(saved));
        return new ApiTokenDtos.CreateResponse(toRow(saved, creators, Instant.now()), plaintext);
    }

    @Transactional
    public ApiTokenDtos.Row revoke(Long id) {
        ApiToken t = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Token not found"));
        if (t.getRevokedAt() == null) {
            t.setRevokedAt(Instant.now());
            repository.save(t);
        }
        return toRow(t, usernamesByIdFor(List.of(t)), Instant.now());
    }

    @Transactional
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Token not found");
        }
        repository.deleteById(id);
    }

    private String mintPlaintext() {
        byte[] buf = new byte[RANDOM_BYTES];
        random.nextBytes(buf);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        return ApiTokenAuthFilter.TOKEN_PREFIX + secret;
    }

    private Map<Long, String> usernamesByIdFor(List<ApiToken> tokens) {
        Map<Long, String> out = new HashMap<>();
        for (ApiToken t : tokens) {
            Long uid = t.getCreatedByUserId();
            if (uid == null || out.containsKey(uid)) continue;
            userRepository.findById(uid).ifPresent(u -> out.put(uid,
                    u.getDisplayName() != null && !u.getDisplayName().isBlank()
                            ? u.getDisplayName() : u.getUsername()));
        }
        return out;
    }

    private ApiTokenDtos.Row toRow(ApiToken t, Map<Long, String> creators, Instant now) {
        return new ApiTokenDtos.Row(
                t.getId(),
                t.getName(),
                t.getPrefix(),
                t.getCreatedByUserId(),
                t.getCreatedByUserId() == null ? null : creators.get(t.getCreatedByUserId()),
                t.getCreatedAt(),
                t.getExpiresAt(),
                t.getRevokedAt(),
                t.getLastUsedAt(),
                t.isUsable(now)
        );
    }
}
