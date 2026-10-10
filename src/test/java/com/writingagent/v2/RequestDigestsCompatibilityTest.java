package com.writingagent.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.writingagent.v2.application.support.RequestDigests;
import org.junit.jupiter.api.Test;

/**
 * Fixed vectors proving RequestDigests algorithm output unchanged after package move.
 * Vectors computed against pre-move algorithm (same join/normalize/SHA-256 rules).
 */
class RequestDigestsCompatibilityTest {

    @Test
    void of_normalizesWhitespaceAndNullParts() {
        assertEquals(
                "5ad0105e612f36b73fef0c24a88ef854fcd861ce93734121558103a8730e4771",
                RequestDigests.of(
                        "p1", "c1", null, "GENERATE_OR_CONTINUE_PLAN", "  hello   world  ", null));
    }

    @Test
    void of_includesVersionWhenPresent() {
        assertEquals(
                "544a18ea18f2d70e858b1b225fe37c9e9227ac5e561465ca98b23ae6d266b12e",
                RequestDigests.of("p1", "c1", "t1", "OP", "", 3L));
    }

    @Test
    void contentHash_stable() {
        assertEquals(
                "015abd7f5cc57a2dd94b7590f04ad8084273905ee33ec5cebeae62276a97f862",
                RequestDigests.contentHash("{\"a\":1}"));
    }
}
