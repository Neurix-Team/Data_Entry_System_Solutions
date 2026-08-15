package com.dataentry.service;

import com.dataentry.dto.TicketDtos;
import com.dataentry.model.CustomField;
import com.dataentry.model.FieldType;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the dedup'd translation cache for a ticket (or a batch of them) before we enter the
 * DB transaction. Split out of {@link TicketService} so the translation walk lives on its own
 * and can be tested in isolation.
 *
 * <p>Only text/textarea/select custom fields are translated — numbers, dates and URLs are
 * mirrored back untouched by the caller.
 */
@Component
public class TicketTranslationPreparer {

    private static final Set<FieldType> TRANSLATABLE_TYPES =
            EnumSet.of(FieldType.TEXT, FieldType.TEXTAREA, FieldType.SELECT);

    private final TranslationService translator;

    public TicketTranslationPreparer(TranslationService translator) {
        this.translator = translator;
    }

    /** Translation cache for a single-ticket create. */
    public Map<String, TranslationService.Bilingual> prepareForOne(
            String title, String content, String websiteName,
            Map<String, String> customValues, List<CustomField> fields) {
        Map<String, TranslationService.Bilingual> out = new HashMap<>();
        addIfNeeded(out, title);
        addIfNeeded(out, content);
        addIfNeeded(out, websiteName);
        addCustomValues(out, customValues, fields);
        return out;
    }

    /** Translation cache spanning every article + the shared custom values in a bulk request. */
    public Map<String, TranslationService.Bilingual> prepareForBulk(
            List<TicketDtos.ArticleRequest> articles,
            Map<String, String> customValues, List<CustomField> fields) {
        Map<String, TranslationService.Bilingual> out = new HashMap<>();
        addCustomValues(out, customValues, fields);
        if (articles != null) {
            for (TicketDtos.ArticleRequest a : articles) {
                addIfNeeded(out, a.title());
                addIfNeeded(out, a.content());
                addIfNeeded(out, a.websiteName());
            }
        }
        return out;
    }

    /**
     * Retrieve a cached bilingual pair. Blank inputs collapse to empty strings so callers
     * don't need to null-check; texts not in the cache (edge cases, later mutations) mirror
     * the input into both languages instead of erroring — the row still saves.
     */
    public TranslationService.Bilingual lookup(
            Map<String, TranslationService.Bilingual> cache, String text) {
        if (text == null || text.isBlank()) return new TranslationService.Bilingual("", "");
        TranslationService.Bilingual b = cache.get(text.trim());
        return b != null ? b : new TranslationService.Bilingual(text, text);
    }

    private void addIfNeeded(Map<String, TranslationService.Bilingual> cache, String text) {
        if (text == null) return;
        String cleaned = text.trim();
        if (cleaned.isEmpty() || cache.containsKey(cleaned)) return;
        cache.put(cleaned, translator.toBoth(cleaned));
    }

    private void addCustomValues(Map<String, TranslationService.Bilingual> cache,
                                 Map<String, String> customValues, List<CustomField> fields) {
        if (customValues == null || fields == null) return;
        for (CustomField f : fields) {
            if (!TRANSLATABLE_TYPES.contains(f.getType())) continue;
            addIfNeeded(cache, customValues.get(f.getFieldKey()));
        }
    }
}
