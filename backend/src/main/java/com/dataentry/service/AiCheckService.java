package com.dataentry.service;

import com.dataentry.dto.AiCheckDtos;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stub implementation of AI grammar / spelling check.
 * Replace {@link #check(String)} with a real LLM call (Claude / OpenAI) later.
 */
@Service
public class AiCheckService {

    private static final Pattern MULTIPLE_SPACES = Pattern.compile(" {2,}");
    private static final Pattern SPACE_BEFORE_PUNCT = Pattern.compile("\\s+([,.;:!?])");
    private static final Pattern MISSING_SPACE_AFTER_PUNCT = Pattern.compile("([,.;:!?])(?=[A-Za-z])");
    private static final Pattern MULTIPLE_NEWLINES = Pattern.compile("\n{3,}");

    public AiCheckDtos.CheckResponse check(String input) {
        String original = input == null ? "" : input;
        String corrected = original;
        List<String> notes = new ArrayList<>();

        String beforeTrim = corrected;
        corrected = corrected.strip();
        if (!beforeTrim.equals(corrected)) notes.add("Trimmed leading/trailing whitespace.");

        String beforeSpaces = corrected;
        corrected = MULTIPLE_SPACES.matcher(corrected).replaceAll(" ");
        if (!beforeSpaces.equals(corrected)) notes.add("Collapsed repeated spaces.");

        Matcher m1 = SPACE_BEFORE_PUNCT.matcher(corrected);
        if (m1.find()) {
            corrected = m1.replaceAll("$1");
            notes.add("Removed spaces before punctuation.");
        }

        Matcher m2 = MISSING_SPACE_AFTER_PUNCT.matcher(corrected);
        if (m2.find()) {
            corrected = m2.replaceAll("$1 ");
            notes.add("Added missing spaces after punctuation.");
        }

        String beforeNl = corrected;
        corrected = MULTIPLE_NEWLINES.matcher(corrected).replaceAll("\n\n");
        if (!beforeNl.equals(corrected)) notes.add("Collapsed excessive blank lines.");

        corrected = capitalizeSentences(corrected, notes);

        if (notes.isEmpty()) notes.add("No changes suggested — content looks good.");

        return new AiCheckDtos.CheckResponse(original, corrected, notes);
    }

    private String capitalizeSentences(String text, List<String> notes) {
        if (text.isEmpty()) return text;
        StringBuilder out = new StringBuilder(text.length());
        boolean capitalizeNext = true;
        boolean changed = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (capitalizeNext && Character.isLetter(c)) {
                char up = Character.toUpperCase(c);
                if (up != c) changed = true;
                out.append(up);
                capitalizeNext = false;
            } else {
                out.append(c);
                if (c == '.' || c == '!' || c == '?' || c == '\n') {
                    capitalizeNext = true;
                } else if (!Character.isWhitespace(c)) {
                    capitalizeNext = false;
                }
            }
        }
        if (changed) notes.add("Capitalized the first letter of sentences.");
        return out.toString();
    }
}
