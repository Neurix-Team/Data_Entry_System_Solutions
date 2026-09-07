package com.dataentry.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class PasswordPolicy {

    static final int MIN_LENGTH = 12;
    static final int MAX_LENGTH = 200;

    static final Set<String> COMMON = Set.of(
            "password", "password1", "password12", "password123",
            "admin", "admin123", "administrator",
            "letmein", "welcome", "welcome1", "changeme",
            "qwerty", "qwerty123", "qwertyuiop",
            "abcdefgh", "abc12345",
            "12345678", "123456789", "1234567890",
            "iloveyou", "monkey", "dragon", "sunshine",
            "superadmin", "superadmin123",
            "dataentry", "dataentry123"
    );

    public enum Violation {
        TOO_SHORT,
        TOO_LONG,
        MISSING_LETTER,
        MISSING_DIGIT,
        COMMON_PASSWORD,
        CONTAINS_USERNAME
    }

    public List<Violation> validate(String password, String username) {
        if (password == null || password.length() < MIN_LENGTH) {
            return List.of(Violation.TOO_SHORT);
        }
        if (password.length() > MAX_LENGTH) {
            return List.of(Violation.TOO_LONG);
        }

        List<Violation> violations = new java.util.ArrayList<>(2);

        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit  = password.chars().anyMatch(Character::isDigit);
        if (!hasLetter) violations.add(Violation.MISSING_LETTER);
        if (!hasDigit) violations.add(Violation.MISSING_DIGIT);

        if (COMMON.contains(password.toLowerCase())) {
            violations.add(Violation.COMMON_PASSWORD);
        }

        if (username != null && !username.isBlank()
                && password.toLowerCase().contains(username.trim().toLowerCase())) {
            violations.add(Violation.CONTAINS_USERNAME);
        }

        return violations;
    }

    public static String describe(Violation v) {
        return switch (v) {
            case TOO_SHORT -> "Password must be at least " + MIN_LENGTH + " characters.";
            case TOO_LONG -> "Password must be at most " + MAX_LENGTH + " characters.";
            case MISSING_LETTER -> "Password must contain at least one letter.";
            case MISSING_DIGIT -> "Password must contain at least one digit.";
            case COMMON_PASSWORD -> "This password appears on public breach lists — pick a unique one.";
            case CONTAINS_USERNAME -> "Password must not contain your username.";
        };
    }
}
