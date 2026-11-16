package com.recruitpro.audit.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure helpers of {@link AuditService}: export bounds, CSV cells, detail sanitising. */
class AuditServiceTest {

    @Test
    void onlyADateWithoutTimeIsAPlainDate() {
        assertTrue(AuditService.isPlainDate("2026-11-08"));
        assertTrue(AuditService.isPlainDate(" 2026-11-08 "));
        assertFalse(AuditService.isPlainDate("2026-11-08T10:15:30Z"));
        assertFalse(AuditService.isPlainDate("2026-11-08T10:15:30"));
        assertFalse(AuditService.isPlainDate(null));
        assertFalse(AuditService.isPlainDate(""));
        assertFalse(AuditService.isPlainDate("yesterday"));
    }

    @Test
    void cellsNeutraliseFormulasAndQuoteSeparators() {
        assertEquals("", AuditService.cell(null));
        assertEquals("\"'=HYPERLINK(\"\"x\"\")\"", AuditService.cell("=HYPERLINK(\"x\")"));
        assertEquals("'+1", AuditService.cell("+1"));
        assertEquals("'@SUM(A1)", AuditService.cell("@SUM(A1)"));
        assertEquals("\"a,b\"", AuditService.cell("a,b"));
        assertEquals("plain", AuditService.cell("plain"));
    }

    @Test
    void secretsInDetailsAreMasked() {
        assertEquals("password=*** token: ***", AuditService.sanitize("password=hunter2 token: abc"));
        assertNull(AuditService.sanitize(null));
    }
}
