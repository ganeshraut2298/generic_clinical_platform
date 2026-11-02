package com.sahaj.opd.application;

import java.util.Map;

/** Fields of {@code PUT /case-paper-settings} (SAH-7). */
public record CasePaperInput(String size, String secondLanguage, Boolean preprinted, Boolean caps,
                              Map<String, Boolean> sections) {
}
