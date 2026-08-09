package com.dataentry.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Locale;

/**
 * Wraps a LibreTranslate endpoint. On any failure (network, non-2xx, malformed body) returns the
 * input unchanged if fail-open is enabled — translations are a nice-to-have, they must never block
 * a save.
 */
@Service
public class TranslationService {

    public enum Lang {
        EN("en"), AR("ar");
        public final String code;
        Lang(String code) { this.code = code; }
    }

    public record Bilingual(String en, String ar) {}

    private static final Logger log = LoggerFactory.getLogger(TranslationService.class);

    private final RestClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String apiKey;
    private final boolean failOpen;

    public TranslationService(
            @Value("${app.translation.base-url:https://libretranslate.com}") String baseUrl,
            @Value("${app.translation.api-key:}") String apiKey,
            @Value("${app.translation.timeout-ms:8000}") int timeoutMs,
            @Value("${app.translation.fail-open:true}") boolean failOpen
    ) {
        String cleaned = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        // Fail fast on a misconfigured base URL rather than letting the app start with a
        // half-broken translation pipeline that silently fails-open on every request.
        if (cleaned.isEmpty() || !(cleaned.startsWith("http://") || cleaned.startsWith("https://"))) {
            throw new IllegalStateException(
                    "app.translation.base-url must be a full http(s) URL, got: '" + baseUrl + "'");
        }
        this.baseUrl = cleaned;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.failOpen = failOpen;
        this.http = RestClient.builder()
                .baseUrl(this.baseUrl)
                .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {{
                    setConnectTimeout((int) Duration.ofMillis(timeoutMs).toMillis());
                    setReadTimeout((int) Duration.ofMillis(timeoutMs).toMillis());
                }})
                .build();
    }

    /**
     * Translate a single string into both English and Arabic. Source language is auto-detected
     * from the input: any Arabic character (Unicode block ؀–ۿ) → treat as Arabic input.
     */
    public Bilingual toBoth(String input) {
        if (input == null || input.isBlank()) {
            return new Bilingual(input, input);
        }
        Lang source = detect(input);
        Lang target = (source == Lang.EN) ? Lang.AR : Lang.EN;
        String translated = translate(input, source, target);
        return source == Lang.EN
                ? new Bilingual(input, translated)
                : new Bilingual(translated, input);
    }

    /**
     * Detect whether a string is Arabic or English. Presence of any Arabic-block char → Arabic;
     * otherwise English (Latin, digits, symbols).
     */
    public Lang detect(String s) {
        if (s == null) return Lang.EN;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x0600 && c <= 0x06FF) return Lang.AR;
        }
        return Lang.EN;
    }

    /**
     * Low-level translate — calls LibreTranslate /translate. Returns input unchanged on failure
     * when fail-open is enabled (the default).
     */
    public String translate(String text, Lang from, Lang to) {
        if (text == null || text.isBlank() || from == to) return text;
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("q", text);
            body.put("source", from.code);
            body.put("target", to.code);
            body.put("format", "text");
            if (!apiKey.isEmpty()) body.put("api_key", apiKey);

            String responseBody = http.post()
                    .uri("/translate")
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(body.toString())
                    .retrieve()
                    .body(String.class);

            JsonNode node = mapper.readTree(responseBody);
            JsonNode translated = node.get("translatedText");
            if (translated == null || translated.isNull()) {
                log.warn("LibreTranslate response missing translatedText: {}", responseBody);
                return failOpenOr(text);
            }
            return translated.asText();
        } catch (Exception e) {
            log.warn("Translation failed ({} → {}): {}", from.code, to.code, e.getMessage());
            return failOpenOr(text);
        }
    }

    private String failOpenOr(String fallback) {
        if (failOpen) return fallback;
        throw new IllegalStateException("Translation failed and fail-open is disabled");
    }

    /**
     * Parse Accept-Language header — returns AR if any Arabic tag appears in the header,
     * otherwise EN. Deliberately simple; the app only supports two languages.
     */
    public static Lang parseAcceptLanguage(String header) {
        if (header == null) return Lang.EN;
        String lower = header.toLowerCase(Locale.ROOT);
        return lower.contains("ar") ? Lang.AR : Lang.EN;
    }
}
