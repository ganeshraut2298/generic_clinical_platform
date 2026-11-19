package com.sahaj.pharmacy.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The fixed CSV template (docs/api/pharmacy.yaml GET /opening-stock/template). */
class OpeningStockCsvTest {

    @Test
    void theTemplateIsTheHeaderAndOneExampleRowTheBrowserSkips() {
        assertThat(OpeningStockCsv.TEMPLATE).isEqualTo("store code,product code,batch,expiry date,MRP,purchase rate,quantity\n"
                + "#EXAMPLE,PRODUCT-CODE,TESTB001,31-12-2027,45.50,30.25,100\n");
        // N-13: the example row names no real or sample catalogue code
        assertThat(OpeningStockCsv.TEMPLATE).doesNotContain("G-000");
        assertThat(OpeningStockCsv.FILE_NAME).isEqualTo("sahaj-opening-stock-template.csv");
        assertThat(OpeningStockCsv.HEADER_MESSAGE).isEqualTo("Use the Sahaj template. Save the file as CSV.");
    }

    @Test
    void theHeaderMatchesTrimmedIgnoringCaseWithAByteOrderMark() {
        List<String> found = new ArrayList<>(OpeningStockCsv.HEADER);
        found.set(0, "﻿STORE CODE ");
        found.set(4, " mrp");
        assertThat(OpeningStockCsv.matchesHeader(found)).isTrue();
        assertThat(OpeningStockCsv.matchesHeader(OpeningStockCsv.HEADER)).isTrue();
    }

    @Test
    void aWrongOrMissingOrReorderedOrExtraColumnDoesNotMatch() {
        assertThat(OpeningStockCsv.matchesHeader(null)).isFalse();
        assertThat(OpeningStockCsv.matchesHeader(List.of())).isFalse();
        assertThat(OpeningStockCsv.matchesHeader(OpeningStockCsv.HEADER.subList(0, 6))).isFalse();
        List<String> extra = new ArrayList<>(OpeningStockCsv.HEADER);
        extra.add("notes");
        assertThat(OpeningStockCsv.matchesHeader(extra)).isFalse();
        List<String> swapped = new ArrayList<>(OpeningStockCsv.HEADER);
        swapped.set(2, "product code");
        swapped.set(1, "batch");
        assertThat(OpeningStockCsv.matchesHeader(swapped)).isFalse();
        List<String> nulls = new ArrayList<>(OpeningStockCsv.HEADER);
        nulls.set(3, null);
        assertThat(OpeningStockCsv.matchesHeader(nulls)).isFalse();
    }
}
