package com.recruitpro.common.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** CSV cells are quoted and never evaluated as spreadsheet formulas (CSV injection). */
class CsvTest {

    @Test
    void textCellsAreQuotedAndFormulasNeutralised() {
        assertEquals("", Csv.text(null));
        assertEquals("\"plain\"", Csv.text("plain"));
        assertEquals("\"a,b\"", Csv.text("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", Csv.text("say \"hi\""));
        assertEquals("\"line1\nline2\"", Csv.text("line1\nline2"));
        assertEquals("\"'=HYPERLINK(\"\"http://x\"\")\"", Csv.text("=HYPERLINK(\"http://x\")"));
        assertEquals("\"'+1+1\"", Csv.text("+1+1"));
        assertEquals("\"'-2+3\"", Csv.text("-2+3"));
        assertEquals("\"'@SUM(A1)\"", Csv.text("@SUM(A1)"));
        assertEquals("\"'\tcmd\"", Csv.text("\tcmd"));
        assertEquals("\"'  =1+1\"", Csv.text("  =1+1"), "leading spaces do not hide a formula");
        assertEquals("\"'\r\n=1\"", Csv.text("\r\n=1"));
        assertEquals("\"a=b\"", Csv.text("a=b"), "only a leading formula character matters");
    }

    @Test
    void numbersBooleansAndRows() {
        assertEquals("", Csv.number(null));
        assertEquals("42", Csv.number(42L));
        assertEquals("87.5", Csv.number(new BigDecimal("87.5")));
        assertEquals("100", Csv.number(new BigDecimal("1E+2")));
        assertEquals("", Csv.bool(null));
        assertEquals("true", Csv.bool(Boolean.TRUE));
        assertEquals("1,\"x\",\r\n", Csv.row(List.of("1", "\"x\"", "")));
    }
}
