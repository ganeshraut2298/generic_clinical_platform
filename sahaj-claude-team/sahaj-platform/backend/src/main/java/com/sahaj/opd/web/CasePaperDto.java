package com.sahaj.opd.web;

import com.sahaj.opd.application.CasePaperInput;
import com.sahaj.opd.application.CasePaperRepository.PaperRow;
import java.util.Map;

/** {@code CasePaperSettings} in {@code docs/api/opd.yaml}. {@code secondLanguage} is always present: null means English only. */
public record CasePaperDto(String size, String secondLanguage, Boolean preprinted, Boolean caps,
                            Map<String, Boolean> sections) {

    public static CasePaperDto from(PaperRow row) {
        return new CasePaperDto(row.size(), row.secondLanguage(), row.preprinted(), row.caps(), row.sections());
    }

    public CasePaperInput toInput() {
        return new CasePaperInput(size, secondLanguage, preprinted, caps, sections);
    }
}
