package com.dataentry.service;

import com.dataentry.repository.PushSubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;


class WebPushServiceTest {

    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    @Test
    void encryptRoundTrip_recoversExactPayload() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair browser = kpg.generateKeyPair();
        byte[] uaPublicKey = point(browser);
        byte[] authSecret = new byte[16];
        new SecureRandom().nextBytes(authSecret);
        byte[] payload = "{\"title\":\"Data Entry\",\"body\":\"Entry approved\"}"
                .getBytes(StandardCharsets.UTF_8);

        WebPushService service = disabledService();
        byte[] wire = service.encrypt(payload, uaPublicKey, authSecret);

        // Header per RFC 8188: salt(16) rs(4 BE) idlen(1) keyid(idlen) ciphertext.
        assertThat(wire.length).isGreaterThan(21 + 65 + payload.length);
        byte[] salt = Arrays.copyOfRange(wire, 0, 16);
        int rs = ((wire[16] & 0xff) << 24) | ((wire[17] & 0xff) << 16)
                | ((wire[18] & 0xff) << 8) | (wire[19] & 0xff);
        assertThat(rs).isEqualTo(4096);
        assertThat(wire[20] & 0xff).isEqualTo(65);
        byte[] serverPoint = Arrays.copyOfRange(wire, 21, 21 + 65);
        assertThat(serverPoint[0]).isEqualTo((byte) 0x04);
        byte[] ciphertext = Arrays.copyOfRange(wire, 21 + 65, wire.length);

        // Independent RFC 8291 receive side, using the browser private key.
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(browser.getPrivate());
        agreement.doPhase(parsePoint(serverPoint), true);
        byte[] ecdhSecret = agreement.generateSecret();

        byte[] ikmInfo = concat("WebPush: info".getBytes(StandardCharsets.UTF_8),
                new byte[]{0}, uaPublicKey, serverPoint);
        byte[] ikm = hkdf(authSecret, ecdhSecret, ikmInfo, 32);
        byte[] cek = hkdf(salt, ikm, "Content-Encoding: aes128gcm"
                .getBytes(StandardCharsets.UTF_8), 16);
        byte[] nonce = hkdf(salt, ikm, "Content-Encoding: nonce"
                .getBytes(StandardCharsets.UTF_8), 12);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"),
                new GCMParameterSpec(128, nonce));
        byte[] content = cipher.doFinal(ciphertext);
        assertThat(content[content.length - 1]).isEqualTo((byte) 0x02);

        byte[] decrypted = Arrays.copyOf(content, content.length - 1);
        assertThat(decrypted).isEqualTo(payload);
        assertThat(new String(decrypted, StandardCharsets.UTF_8)).contains("Entry approved");
    }

    @Test
    void vapidKeys_enableTheServiceAndRoundTrip() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair vapid = kpg.generateKeyPair();
        String scalar = B64URL.encodeToString(
                fixed(((ECPrivateKey) vapid.getPrivate()).getS().toByteArray(), 32));
        String point = B64URL.encodeToString(point(vapid));

        WebPushService service = new WebPushService(
                Mockito.mock(PushSubscriptionRepository.class), point, scalar,
                "mailto:ops@example.com", 3600);

        assertThat(service.isEnabled()).isTrue();
        assertThat(service.vapidPublicKey()).isEqualTo(point);
    }

    @Test
    void missingVapidKeys_leavesPushDisabled() {
        WebPushService service = new WebPushService(
                Mockito.mock(PushSubscriptionRepository.class), "", "",
                "mailto:ops@example.com", 3600);
        assertThat(service.isEnabled()).isFalse();
        assertThat(service.vapidPublicKey()).isNull();
    }

    // ── helpers (an intentional second implementation, not shared with prod code) ──

    private static WebPushService disabledService() {
        return new WebPushService(Mockito.mock(PushSubscriptionRepository.class),
                "", "", "mailto:ops@example.com", 3600);
    }

    private static byte[] point(KeyPair pair) {
        ECPoint w = ((ECPublicKey) pair.getPublic()).getW();
        byte[] out = new byte[65];
        out[0] = 0x04;
        System.arraycopy(fixed(w.getAffineX().toByteArray(), 32), 0, out, 1, 32);
        System.arraycopy(fixed(w.getAffineY().toByteArray(), 32), 0, out, 33, 32);
        return out;
    }

    private static PublicKey parsePoint(byte[] uncompressed) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        java.security.spec.ECParameterSpec params = ((ECPublicKey) generator.generateKeyPair().getPublic()).getParams();
        java.math.BigInteger x = new java.math.BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 33));
        java.math.BigInteger y = new java.math.BigInteger(1, Arrays.copyOfRange(uncompressed, 33, 65));
        return KeyFactory.getInstance("EC")
                .generatePublic(new ECPublicKeySpec(new ECPoint(x, y), params));
    }

    private static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        byte[] out = new byte[length];
        int produced = 0;
        byte[] previous = new byte[0];
        for (byte counter = 1; produced < length; counter++) {
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

    private static byte[] fixed(byte[] raw, int length) {
        byte[] out = new byte[length];
        if (raw.length > length) {
            System.arraycopy(raw, raw.length - length, out, 0, length);
        } else {
            System.arraycopy(raw, 0, out, length - raw.length, raw.length);
        }
        return out;
    }
}
