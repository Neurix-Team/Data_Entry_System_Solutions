package com.dataentry.service;

import com.dataentry.dto.TicketDtos;
import com.dataentry.model.CustomField;
import com.dataentry.model.FieldType;
import com.dataentry.model.Subcategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Direct coverage of the translation preparer that was pulled out of TicketService. These
 * tests protect the dedup guarantees the ticket flow leans on — every duplicate string
 * translated once, blanks skipped, non-text field types left alone.
 */
@ExtendWith(MockitoExtension.class)
class TicketTranslationPreparerTest {

    @Mock TranslationService translator;

    private TicketTranslationPreparer preparer;

    @BeforeEach
    void setup() {
        preparer = new TicketTranslationPreparer(translator);
        // Lenient: several tests here exercise the blank-input paths that shouldn't touch
        // the translator at all — strict stubbing would then complain about "unused" stub.
        lenient().when(translator.toBoth(anyString()))
                .thenAnswer(inv -> new TranslationService.Bilingual(
                        "EN:" + inv.getArgument(0), "AR:" + inv.getArgument(0)));
    }

    @Test
    void prepareForOne_skipsNullAndBlankStrings() {
        preparer.prepareForOne(null, "  ", "", null, List.of());
        verify(translator, never()).toBoth(anyString());
    }

    @Test
    void prepareForOne_dedupesIdenticalTextAcrossFields() {
        // Same string reused as title, content, and website name → translator called ONCE.
        Map<String, TranslationService.Bilingual> cache = preparer.prepareForOne(
                "same", "same", "same", null, List.of());

        verify(translator, times(1)).toBoth("same");
        assertThat(cache).containsKey("same");
    }

    @Test
    void prepareForOne_trimsWhitespaceBeforeCaching() {
        preparer.prepareForOne(" hello ", "hello", null, null, List.of());
        // Both entries normalise to "hello" → still a single translator call.
        verify(translator, times(1)).toBoth("hello");
    }

    @Test
    void prepareForOne_translatesOnlyTextlikeCustomFields() {
        CustomField textField = field("topic", FieldType.TEXT);
        CustomField numberField = field("count", FieldType.NUMBER);
        CustomField urlField = field("link", FieldType.URL);
        CustomField selectField = field("category", FieldType.SELECT);

        Map<String, String> values = Map.of(
                "topic", "hello",
                "count", "42",
                "link", "https://example.com",
                "category", "News");

        preparer.prepareForOne(null, null, null, values,
                List.of(textField, numberField, urlField, selectField));

        verify(translator).toBoth("hello");
        verify(translator).toBoth("News");
        verify(translator, never()).toBoth("42");
        verify(translator, never()).toBoth("https://example.com");
    }

    @Test
    void prepareForBulk_walksEveryArticleAndDedupes() {
        TicketDtos.ArticleRequest a = new TicketDtos.ArticleRequest(
                "Shared", "unique A", null, null, null, null);
        TicketDtos.ArticleRequest b = new TicketDtos.ArticleRequest(
                "Shared", "unique B", null, null, null, null);

        preparer.prepareForBulk(List.of(a, b), Map.of(), List.of());

        // "Shared" appears in both articles' titles → still just one translator call.
        verify(translator, times(1)).toBoth("Shared");
        verify(translator, times(1)).toBoth("unique A");
        verify(translator, times(1)).toBoth("unique B");
    }

    @Test
    void lookup_mirrorsBlankInput() {
        TranslationService.Bilingual bi = preparer.lookup(Map.of(), "   ");
        assertThat(bi.en()).isEmpty();
        assertThat(bi.ar()).isEmpty();
    }

    @Test
    void lookup_mirrorsUncachedText() {
        // Text was never registered — preparer must not error, and must mirror the input in
        // both languages so downstream .setTitleEn/.setTitleAr calls still succeed.
        TranslationService.Bilingual bi = preparer.lookup(Map.of(), "not in cache");
        assertThat(bi.en()).isEqualTo("not in cache");
        assertThat(bi.ar()).isEqualTo("not in cache");
    }

    @Test
    void lookup_returnsCachedBilingualWhenPresent() {
        Map<String, TranslationService.Bilingual> cache =
                preparer.prepareForOne("hello", null, null, null, List.of());
        TranslationService.Bilingual bi = preparer.lookup(cache, "hello");
        assertThat(bi.en()).isEqualTo("EN:hello");
        assertThat(bi.ar()).isEqualTo("AR:hello");
    }

    private CustomField field(String key, FieldType type) {
        return CustomField.builder()
                .subcategory(Subcategory.builder().id(1L).build())
                .fieldKey(key).label(key).type(type)
                .active(true).displayOrder(0).build();
    }
}
