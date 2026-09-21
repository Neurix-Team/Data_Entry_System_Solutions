package com.dataentry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.dataentry.model.PushSubscription;
import com.dataentry.model.User;
import com.dataentry.repository.PushSubscriptionRepository;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Web Push — RFC 8030 delivery, RFC 8188 + RFC 8291 payload encryption, RFC 8292
 * VAPID authorization — built on plain JCA plus the jjwt already on the classpath.
 * Deliberately no extra crypto dependency: dragging in a Web-Push library would pull
 * Bouncy Castle versions into an app that otherwise needs none.
 *
 * Delivery is fire-and-forget on a small daemon pool: NotificationService only
 * registers work after the surrounding transaction commits, and a slow push service
 * can never hold up the request that produced the notification.
 */
@Service
public class WebPushService {

    private static final Logger log = LoggerFactory.getLogger(WebPushService.class);
    private static final Base64.Decoder B64URL_DEC = Base64.getUrlDecoder();
    private static final Base64.Encoder B64URL_ENC = Base64.getUrlEncoder().withoutPadding();
    /** Services reject anything past ~4 KB after encryption; stay comfortably under. */
    private static final int MAX_PAYLOAD_BYTES = 3072;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ECParameterSpec P256_PARAMS = p256Params();

    private final PushSubscriptionRepository subscriptions;
    private final String vapidPublicKey;
    private final PrivateKey vapidPrivateKey;
    private final String vapidSubject;
    private final int ttlSeconds;
    private final boolean enabled;
    private final HttpClient http;
    private final ExecutorService sender;
    private final ObjectMapper json = new ObjectMapper();

    // ── CONSTRUCTOR_SECTION ──

    public WebPushService(PushSubscriptionRepository subscriptions,
                          @Value("${app.push.vapid-public-key:}") String vapidPublicKey,
                          @Value("${app.push.vapid-private-key:}") String vapidPrivateKey,
                          @Value("${app.push.vapid-subject:mailto:admin@example.com}") String vapidSubject,
                          @Value("${app.push.ttl-seconds:3600}") int ttlSeconds) {
        this.subscriptions = subscriptions;
        this.vapidSubject = vapidSubject == null || vapidSubject.isBlank()
                ? "mailto:admin@example.com" : vapidSubject.trim();
        this.ttlSeconds = Math.max(60, ttlSeconds);
        String publicKey = vapidPublicKey == null ? "" : vapidPublicKey.trim();
        PrivateKey key = null;
        boolean ok = !publicKey.isEmpty() && vapidPrivateKey != null && !vapidPrivateKey.isBlank();
        if (ok) {
            try {
                key = parseEcPrivateKey(vapidPrivateKey.trim());
            } catch (Exception e) {
                log.error("Web Push disabled: the VAPID private key is malformed ({}).", e.toString());
                ok = false;
            }
        } else {
            log.warn("Web Push disabled: no VAPID keys configured "
                    + "(WEB_PUSH_VAPID_PUBLIC_KEY / WEB_PUSH_VAPID_PRIVATE_KEY).");
        }
        this.vapidPublicKey = publicKey;
        this.vapidPrivateKey = key;
        this.enabled = ok;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.sender = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "web-push");
            t.setDaemon(true);
            return t;
        });
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Application-server key for PushManager.subscribe — null when push is off. */
    public String vapidPublicKey() {
        return enabled ? vapidPublicKey : null;
    }

    @PreDestroy
    void shutdown() {
        sender.shutdownNow();
    }

    // ── SUBSCRIPTIONS_SECTION ──

    /** Idempotent upsert keyed by the (globally unique) browser endpoint. */
    public void subscribe(User user, String endpoint, String p256dh, String auth, String userAgent) {
        requireEnabled();
        if (user == null || user.getId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        if (endpoint == null || endpoint.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing push endpoint");
        }
        String trimmed = endpoint.trim();
        assertPublicEndpoint(trimmed);
        String clientKey = B64URL_ENC.encodeToString(decodeKeyMaterial(p256dh, 65, "p256dh"));
        String authSecret = B64URL_ENC.encodeToString(decodeKeyMaterial(auth, 16, "auth"));

        PushSubscription sub = subscriptions.findByEndpoint(trimmed).orElse(null);
        if (sub == null) {
            sub = PushSubscription.builder()
                    .user(user)
                    .endpoint(trimmed)
                    .p256dh(clientKey)
                    .auth(authSecret)
                    .userAgent(truncate(userAgent, 300))
                    .createdAt(Instant.now())
                    .build();
        } else {
            // Same browser, possibly a different account now: the newest opt-in wins.
            sub.setUser(user);
            sub.setP256dh(clientKey);
            sub.setAuth(authSecret);
            sub.setUserAgent(truncate(userAgent, 300));
        }
        subscriptions.save(sub);
    }

    public void unsubscribe(User user, String endpoint) {
        if (user == null || user.getId() == null || endpoint == null || endpoint.isBlank()) return;
        subscriptions.deleteByEndpointAndUserId(endpoint.trim(), user.getId());
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Web Push is not configured on this server");
        }
    }

    /**
     * Endpoints are user-supplied URLs this backend will POST to, so the same SSRF
     * rules as ticket website links apply: https only, no private or loopback hosts.
     */
    private void assertPublicEndpoint(String endpoint) {
        final URI uri;
        try {
            uri = new URI(endpoint);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed push endpoint");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase("https")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Push endpoint must use https");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Push endpoint is missing a host");
        }
        String h = host.toLowerCase();
        boolean isPrivate = h.equals("localhost") || h.equals("0.0.0.0")
                || h.equals("169.254.169.254") || h.equals("[::1]") || h.equals("[::]")
                || h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.")
                || h.startsWith("169.254.") || h.matches("^172\\.(1[6-9]|2[0-9]|3[01])\\..*")
                || h.endsWith(".local") || h.endsWith(".internal");
        if (isPrivate) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Push endpoint must point to a public address");
        }
    }

    /**
     * Browsers hand over base64 (padded) or base64url key material; accept both,
     * verify the exact byte length, and hand back raw bytes.
     */
    private byte[] decodeKeyMaterial(String raw, int expectedLength, String name) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing " + name + " key material");
        }
        String s = raw.trim().replace('-', '+').replace('_', '/');
        int pad = s.length() % 4;
        if (pad == 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed " + name + " key material");
        }
        if (pad == 2) s += "==";
        if (pad == 3) s += "=";
        byte[] out;
        try {
            out = Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed " + name + " key material");
        }
        if (out.length != expectedLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    name + " key material must be " + expectedLength + " bytes");
        }
        return out;
    }

    // ── DELIVERY_SECTION ──

    /**
     * Fire-and-forget delivery to every browser the user opted in from. Never throws:
     * a broken push must never break the flow that produced the notification.
     */
    public void notifyUser(Long userId, String title, String body, String url) {
        if (!enabled || userId == null || body == null || body.isBlank()) return;
        String payload = buildPayload(title, body, url);
        sender.execute(() -> {
            List<PushSubscription> subs;
            try {
                subs = subscriptions.findByUserId(userId);
            } catch (Exception e) {
                log.warn("Web Push: could not load subscriptions for user {}: {}", userId, e.toString());
                return;
            }
            for (PushSubscription sub : subs) {
                try {
                    deliver(sub, payload);
                } catch (Exception e) {
                    log.warn("Web Push: delivery to {} failed: {}",
                            maskEndpoint(sub.getEndpoint()), e.toString());
                }
            }
        });
    }

    private String buildPayload(String title, String body, String url) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", title == null || title.isBlank() ? "Data Entry" : truncate(title, 200));
        data.put("body", truncate(body, 800));
        data.put("tag", "dems-notification"); // newer notifications replace older on screen
        if (url != null && !url.isBlank()) data.put("url", truncate(url, 500));
        try {
            return json.writeValueAsString(data);
        } catch (Exception e) {
            // Jackson on a Map of strings cannot realistically fail; stay defensive.
            return "{\"title\":\"Data Entry\",\"body\":\"\",\"tag\":\"dems-notification\"}";
        }
    }

    private void deliver(PushSubscription sub, String payload) throws Exception {
        byte[] body = encrypt(payload.getBytes(StandardCharsets.UTF_8),
                B64URL_DEC.decode(sub.getP256dh()), B64URL_DEC.decode(sub.getAuth()));
        String authorization = "vapid t=" + vapidJwt(originOf(sub.getEndpoint()))
                + ", k=" + vapidPublicKey;
        HttpRequest request = HttpRequest.newBuilder(URI.create(sub.getEndpoint()))
                .timeout(Duration.ofSeconds(8))
                .header("TTL", String.valueOf(ttlSeconds))
                .header("Content-Encoding", "aes128gcm")
                .header("Content-Type", "application/octet-stream")
                .header("Authorization", authorization)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            sub.setLastSuccessAt(Instant.now());
            subscriptions.save(sub);
        } else if (response.statusCode() == 404 || response.statusCode() == 410) {
            // The push service says this subscription is gone for good — drop it so
            // future notifications do not keep paying for a dead endpoint.
            subscriptions.delete(sub);
        } else {
            log.info("Web Push: endpoint returned HTTP {} — leaving the subscription in place.",
                    response.statusCode());
        }
    }

    /** RFC 8292: the VAPID audience is the origin of the push service, not of the app. */
    private String originOf(String endpoint) {
        URI uri = URI.create(endpoint);
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed push endpoint");
        }
        int port = uri.getPort();
        boolean defaultPort = port == -1
                || (scheme.equalsIgnoreCase("https") && port == 443)
                || (scheme.equalsIgnoreCase("http") && port == 80);
        return scheme.toLowerCase() + "://" + host.toLowerCase() + (defaultPort ? "" : ":" + port);
    }

    /** RFC 8292 ES256 JWT — jjwt already produces the JOSE raw r||s signature shape. */
    private String vapidJwt(String audience) {
        Instant now = Instant.now();
        return Jwts.builder()
                .header().add("typ", "JWT").and()
                .audience().add(audience).and()
                .subject(vapidSubject)
                .issuedAt(java.util.Date.from(now))
                .expiration(java.util.Date.from(now.plus(Duration.ofHours(12))))
                .signWith(vapidPrivateKey, Jwts.SIG.ES256)
                .compact();
    }

    // ── CRYPTO_SECTION ──

    /**
     * RFC 8291 key schedule + RFC 8188 aes128gcm framing:
     * <pre>
     * ikm   = HKDF(auth, ECDH(asPriv, uaPub), "WebPush: info" 0x00 uaPub asPub, 32)
     * cek   = HKDF(salt, ikm, "Content-Encoding: aes128gcm", 16)
     * nonce = HKDF(salt, ikm, "Content-Encoding: nonce", 12)
     * body  = salt(16) rs(4 = 4096 BE) idlen(1 = 0) AES-GCM(content || 0x02)
     * </pre>
     * Package-visible so the crypto round-trip test can exercise it directly.
     */
    byte[] encrypt(byte[] plaintext, byte[] uaPublicKey, byte[] authSecret) throws Exception {
        if (plaintext.length + 1 > MAX_PAYLOAD_BYTES) {
            plaintext = Arrays.copyOf(plaintext, MAX_PAYLOAD_BYTES - 1);
        }
        PublicKey clientKey = parseEcPublicKey(uaPublicKey);
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair asPair = kpg.generateKeyPair();

        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(asPair.getPrivate());
        agreement.doPhase(clientKey, true);
        byte[] ecdhSecret = agreement.generateSecret();

        byte[] asPublicPoint = toUncompressedPoint((ECPublicKey) asPair.getPublic());
        byte[] ikmInfo = concat("WebPush: info".getBytes(StandardCharsets.UTF_8),
                new byte[]{0}, uaPublicKey, asPublicPoint);
        byte[] ikm = hkdf(authSecret, ecdhSecret, ikmInfo, 32);

        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] cek = hkdf(salt, ikm, "Content-Encoding: aes128gcm".getBytes(StandardCharsets.UTF_8), 16);
        byte[] nonce = hkdf(salt, ikm, "Content-Encoding: nonce".getBytes(StandardCharsets.UTF_8), 12);

        byte[] content = Arrays.copyOf(plaintext, plaintext.length + 1);
        content[plaintext.length] = 0x02; // last-record delimiter, RFC 8188

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] ciphertext = cipher.doFinal(content);

        // RFC 8188 header: salt(16) rs(4 BE) idlen(1) keyid... — and per RFC 8291 the
        // keyid carries the ephemeral server ECDH point, because that is the only way
        // the browser learns it for the shared-secret derivation.
        byte[] header = new byte[21 + 65];
        System.arraycopy(salt, 0, header, 0, 16);
        header[18] = 0x10; // rs = 4096, big endian
        header[20] = 65;   // idlen — the uncompressed server point follows
        System.arraycopy(asPublicPoint, 0, header, 21, 65);
        return concat(header, ciphertext);
    }

    // ── CRYPTO_HELPERS_SECTION ──

    /** RFC 5869 HKDF: extract = HMAC-SHA256(salt, ikm); expand with info and a counter. */
    private static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt.length == 0 ? new byte[32] : salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        byte[] out = new byte[length];
        int produced = 0;
        byte[] previous = new byte[0];
        for (byte counter = 1; produced < length; counter++) {
            mac.reset();
            mac.update(previous);
            mac.update(info);
            mac.update(counter);
            previous = mac.doFinal();
            int take = Math.min(previous.length, length - produced);
            System.arraycopy(previous, 0, out, produced, take);
            produced += take;
        }
        return out;
    }

    private static ECParameterSpec p256Params() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(new ECGenParameterSpec("secp256r1"));
            return ((ECPublicKey) kpg.generateKeyPair().getPublic()).getParams();
        } catch (Exception e) {
            throw new IllegalStateException("JDK has no P-256 support — Web Push cannot run", e);
        }
    }

    private static PrivateKey parseEcPrivateKey(String base64UrlScalar) throws Exception {
        byte[] raw = B64URL_DEC.decode(base64UrlScalar);
        if (raw.length != 32) {
            throw new IllegalArgumentException("VAPID private key must be exactly 32 bytes");
        }
        return KeyFactory.getInstance("EC").generatePrivate(
                new ECPrivateKeySpec(new java.math.BigInteger(1, raw), P256_PARAMS));
    }

    private static PublicKey parseEcPublicKey(byte[] uncompressedPoint) throws Exception {
        if (uncompressedPoint.length != 65 || uncompressedPoint[0] != 0x04) {
            throw new IllegalArgumentException("Client public key must be a 65-byte uncompressed point");
        }
        java.math.BigInteger x = new java.math.BigInteger(1, Arrays.copyOfRange(uncompressedPoint, 1, 33));
        java.math.BigInteger y = new java.math.BigInteger(1, Arrays.copyOfRange(uncompressedPoint, 33, 65));
        return KeyFactory.getInstance("EC")
                .generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256_PARAMS));
    }

    /** Fixed-width (left-padded) big-endian encoding — the wire format for P-256 points. */
    private static byte[] toUncompressedPoint(ECPublicKey key) {
        ECPoint w = key.getW();
        byte[] out = new byte[65];
        out[0] = 0x04;
        System.arraycopy(fixedLength(w.getAffineX(), 32), 0, out, 1, 32);
        System.arraycopy(fixedLength(w.getAffineY(), 32), 0, out, 33, 32);
        return out;
    }

    private static byte[] fixedLength(java.math.BigInteger value, int length) {
        byte[] raw = value.toByteArray(); // minimal two's-complement, may carry a sign byte
        byte[] out = new byte[length];
        if (raw.length > length) {
            System.arraycopy(raw, raw.length - length, out, 0, length);
        } else {
            System.arraycopy(raw, 0, out, length - raw.length, raw.length);
        }
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) len += p.length;
        byte[] out = new byte[len];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Endpoint URLs identify a browser; log only enough to recognize the subscription. */
    private static String maskEndpoint(String endpoint) {
        if (endpoint == null) return "null";
        int cut = Math.min(40, endpoint.length());
        return endpoint.substring(0, cut) + "…(" + endpoint.length() + ")";
    }
}