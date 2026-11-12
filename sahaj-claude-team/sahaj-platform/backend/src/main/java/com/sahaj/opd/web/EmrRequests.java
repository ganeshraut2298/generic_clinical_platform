package com.sahaj.opd.web;

/**
 * The three request bodies of SAH-44 (docs/api/opd.yaml 0.2.0). Each is closed: a field the contract does not list
 * (a label, a part, a default part, a clinic id) is refused with a 400 by {@link EmrStrictJsonModule}. A missing
 * field is checked by the application service, so a wrong role is answered 403 before anything else about the body.
 */
public final class EmrRequests {

    private EmrRequests() {
    }

    /** PUT /emr/templates/{templateKey}/sections/{sectionKey}/override */
    public record SectionOverride(Boolean shown, Boolean required, Long revision) {
    }

    /** POST /emr/templates/{templateKey}/reset */
    public record TemplateReset(Long revision) {
    }

    /** PUT /emr/settings. There is no default part: it is stored as Assessment and cannot be sent. */
    public record Settings(String sectionPanel, String rightPanelFirst, Long version) {
    }
}
