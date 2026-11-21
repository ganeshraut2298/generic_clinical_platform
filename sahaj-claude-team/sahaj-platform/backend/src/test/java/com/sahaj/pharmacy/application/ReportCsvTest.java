package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The CSV rules of docs/api/pharmacy.yaml 0.3.6: BOM, formula neutralising, plain two-decimal money, IST dates. */
class ReportCsvTest {

    @Test
    void theFileStartsWithTheByteOrderMarkAndEndsEachLineWithCrLf() {
        byte[] bytes = ReportCsv.write(List.of("Store", "Quantity"), List.of(List.of("Test Store", "5")));
        String text = new String(bytes, StandardCharsets.UTF_8);

        assertThat(bytes[0] & 0xFF).isEqualTo(0xEF);
        assertThat(bytes[1] & 0xFF).isEqualTo(0xBB);
        assertThat(bytes[2] & 0xFF).isEqualTo(0xBF);
        assertThat(text).isEqualTo("﻿Store,Quantity\r\nTest Store,5\r\n");
    }

    @Test
    void aTextCellThatWouldStartAFormulaGetsASingleQuote() {
        for (String start : List.of("=1+1", "+1", "-1", "@SUM(A1)", "\tx", "\rx")) {
            assertThat(ReportCsv.cell(start, ReportDef.Kind.TEXT)).startsWith("'");
        }
        assertThat(ReportCsv.cell("Paracetamol", ReportDef.Kind.TEXT)).isEqualTo("Paracetamol");
    }

    @Test
    void aNegativeNumberIsNotGivenAQuote() {
        assertThat(ReportCsv.cell(-1250L, ReportDef.Kind.PAISE)).isEqualTo("-12.50");
        assertThat(ReportCsv.cell(-3, ReportDef.Kind.INTEGER)).isEqualTo("-3");
    }

    @Test
    void valuesWithACommaOrAQuoteAreQuoted() {
        assertThat(ReportCsv.escape("Tab 500, strip")).isEqualTo("\"Tab 500, strip\"");
        assertThat(ReportCsv.escape("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(ReportCsv.escape("plain")).isEqualTo("plain");
    }

    @Test
    void moneyIsAPlainNumberWithTwoDecimalsAndDatesReadInIst() {
        assertThat(ReportCsv.cell(12550L, ReportDef.Kind.PAISE)).isEqualTo("125.50");
        assertThat(ReportCsv.cell(5L, ReportDef.Kind.PAISE)).isEqualTo("0.05");
        assertThat(ReportCsv.cell(LocalDate.of(2026, 10, 24), ReportDef.Kind.DATE)).isEqualTo("24 Oct 2026");
        // 05:35 UTC is 11:05 IST
        assertThat(ReportCsv.cell(Instant.parse("2026-10-24T05:35:00Z"), ReportDef.Kind.DATE_TIME))
                .isEqualTo("24 Oct 2026 11:05 AM IST");
        assertThat(ReportCsv.cell(null, ReportDef.Kind.TEXT)).isEmpty();
    }
}
