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

 
@Service
public class TranslationService {

    public enum Lang {
        EN("en"), AR("ar");
        public final String code;
        Lang(String code) { this.code = code; }
    }

    public record Bilingual(String en, String ar) {}

    private static final Logger log = LoggerFactory.getLogger(TranslationService.class);

    private final RestClient http;   // null when translation is disabled (base-url blank)
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String apiKey;
    private final boolean failOpen;
    private final boolean enabled;

    public TranslationService(
            @Value("${app.translation.base-url:}") String baseUrl,
            @Value("${app.translation.api-key:}") String apiKey,
            @Value("${app.translation.timeout-ms:8000}") int timeoutMs,
            @Value("${app.translation.fail-open:true}") boolean failOpen
    ) {
        String cleaned = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        // Empty URL is a valid config: translation is turned off — bilingual columns will just
        // mirror the input.  A non-empty URL must still look like http(s).
        if (!cleaned.isEmpty() && !(cleaned.startsWith("http://") || cleaned.startsWith("https://"))) {
            throw new IllegalStateException(
                    "app.translation.base-url must be a full http(s) URL, got: '" + baseUrl + "'");
        }
        this.baseUrl = cleaned;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.failOpen = failOpen;
        this.enabled = !cleaned.isEmpty();
        if (this.enabled) {
            this.http = RestClient.builder()
                    .baseUrl(this.baseUrl)
                    .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {{
                        setConnectTimeout((int) Duration.ofMillis(timeoutMs).toMillis());
                        setReadTimeout((int) Duration.ofMillis(timeoutMs).toMillis());
                    }})
                    .build();
        } else {
            this.http = null;
            log.info("TranslationService disabled — bilingual columns will mirror the input. "
                    + "Set TRANSLATION_BASE_URL to enable (e.g. http://libretranslate:5000).");
        }
    }

 
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

    
    public Lang detect(String s) {
        if (s == null) return Lang.EN;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x0600 && c <= 0x06FF) return Lang.AR;
        }
        return Lang.EN;
    }

 
    public String translate(String text, Lang from, Lang to) {
        if (text == null || text.isBlank() || from == to) return text;
        if (!enabled) return text;
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
 
    public static Lang parseAcceptLanguage(String header) {
        if (header == null) return Lang.EN;
        String lower = header.toLowerCase(Locale.ROOT);
        return lower.contains("ar") ? Lang.AR : Lang.EN;
    }
}
