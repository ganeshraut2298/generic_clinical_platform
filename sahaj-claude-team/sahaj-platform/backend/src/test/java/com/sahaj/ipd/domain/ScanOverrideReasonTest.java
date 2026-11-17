package com.sahaj.ipd.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** SAH-76 contract 0.4.3 (ADR-027 A9): the override reasons accepted on input are the three; the old one stays readable. */
class ScanOverrideReasonTest {

    @Test
    void theInputReasonsAreTheThreeInSelectOrder() {
        assertThat(ScanOverrideReason.inputReasons()).containsExactly(ScanOverrideReason.NO_BARCODE,
                ScanOverrideReason.SCANNER_DOWN, ScanOverrideReason.EMERGENCY);
    }

    @Test
    void theWristbandDamagedKeyIsRefusedOnInputButStillReadable() {
        assertThat(ScanOverrideReason.ofInputKey("WRISTBAND_DAMAGED")).isEmpty();
        assertThat(ScanOverrideReason.ofKey("WRISTBAND_DAMAGED")).contains(ScanOverrideReason.WRISTBAND_DAMAGED);
        assertThat(ScanOverrideReason.WRISTBAND_DAMAGED.label()).isEqualTo("Wristband damaged or missing");
    }

    @Test
    void anInputKeyIsMatchedExactly() {
        assertThat(ScanOverrideReason.ofInputKey("NO_BARCODE")).contains(ScanOverrideReason.NO_BARCODE);
        assertThat(ScanOverrideReason.ofInputKey("SCANNER_DOWN")).contains(ScanOverrideReason.SCANNER_DOWN);
        assertThat(ScanOverrideReason.ofInputKey("EMERGENCY")).contains(ScanOverrideReason.EMERGENCY);
        assertThat(ScanOverrideReason.ofInputKey("emergency")).isEmpty();
        assertThat(ScanOverrideReason.ofInputKey("")).isEmpty();
        assertThat(ScanOverrideReason.ofInputKey(null)).isEmpty();
    }
}
