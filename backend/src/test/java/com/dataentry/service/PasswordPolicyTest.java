package com.dataentry.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.dataentry.service.PasswordPolicy.Violation.*;
import static org.assertj.core.api.Assertions.assertThat;

class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy();

    // ---- happy path ----

    @Test
    void accepts_a_strong_password() {
        assertThat(policy.validate("Correct-horse-9-battery-staple", "alice")).isEmpty();
        assertThat(policy.validate("Th1s-is-not-guessable", "bob")).isEmpty();
    }

    // ---- length ----

    @Test
    void rejects_short_passwords_and_reports_only_that() {
        assertThat(policy.validate("Sh0rt!", "alice")).containsExactly(TOO_SHORT);
        assertThat(policy.validate("12345", "alice")).containsExactly(TOO_SHORT);
        assertThat(policy.validate("", "alice")).containsExactly(TOO_SHORT);
        assertThat(policy.validate(null, "alice")).containsExactly(TOO_SHORT);
    }

    @Test
    void rejects_absurdly_long_passwords() {
        String tooLong = "a".repeat(300);
        assertThat(policy.validate(tooLong, "alice")).containsExactly(TOO_LONG);
    }

    // ---- character classes ----

    @Test
    void rejects_letters_only_or_digits_only() {
        assertThat(policy.validate("abcdefghijklmno", "alice")).contains(MISSING_DIGIT);
        assertThat(policy.validate("123456789012345", "alice")).contains(MISSING_LETTER);
    }

    // ---- common list ----

    @Test
    void rejects_common_passwords_regardless_of_case() {
        // Common list is lowercase-matched, so caps still get caught. Both entries here
        // are ≥12 chars so they clear the TOO_SHORT early-return and actually reach the
        // COMMON check — that's the code path the test exists to guard.
        assertThat(policy.validate("Administrator", "alice")).contains(COMMON_PASSWORD);
        assertThat(policy.validate("SUPERADMIN123", "alice")).contains(COMMON_PASSWORD);
        // Common list is exact-match on the whole string, not substring. Suffixes fall out.
        assertThat(policy.validate("Administrator2", "alice")).doesNotContain(COMMON_PASSWORD);
    }

    // ---- username ----

    @Test
    void rejects_password_that_contains_the_username() {
        assertThat(policy.validate("Alice-1234-hello", "alice")).contains(CONTAINS_USERNAME);
        assertThat(policy.validate("myALICEpasscode-1", "alice")).contains(CONTAINS_USERNAME);
        // Empty / null username → check is skipped.
        assertThat(policy.validate("strongenough-1234", null)).isEmpty();
        assertThat(policy.validate("strongenough-1234", "  ")).isEmpty();
    }

    // ---- multi-violation ----

    @Test
    void reports_multiple_independent_violations_at_once() {
        // Twelve chars, letters + username "bob" + on common list ("qwerty123" is; but we
        // want multiple flags — use letters-only + username to trigger 2).
        List<PasswordPolicy.Violation> vs = policy.validate("bobbobbobbobb", "bob");
        assertThat(vs).contains(MISSING_DIGIT, CONTAINS_USERNAME);
    }

    // ---- messages ----

    @Test
    void every_violation_maps_to_a_specific_actionable_message() {
        for (PasswordPolicy.Violation v : PasswordPolicy.Violation.values()) {
            String msg = PasswordPolicy.describe(v);
            assertThat(msg).as(v.name()).isNotBlank();
            // Not just "invalid password" — must include specifics.
            assertThat(msg.length()).isGreaterThan(20);
        }
    }
}
