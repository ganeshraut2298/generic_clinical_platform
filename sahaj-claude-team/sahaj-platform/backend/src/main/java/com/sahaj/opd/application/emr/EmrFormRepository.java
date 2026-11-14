package com.sahaj.opd.application.emr;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port over {@code opd.emr_form}, the immutable {@code opd.emr_form_version} and the global {@code opd.emr_form_starter}
 * (SAH-59; ADR-022 D11). Setup data, no patient data. Row level security limits every statement to the bound clinic and
 * the tenant id is also in each statement. Updates are compare-and-set on the revision; versions are insert-only and kept
 * for ever; nothing is deleted. Definitions are JSON text ({@link EmrFormModel.Definition}).
 */
public interface EmrFormRepository {

    /** One form row. {@code draftDefinition} is null when {@code draftState} is NONE. */
    record FormRow(UUID id, String formKey, String name, String starterKey, String status, int liveVersionNo,
                   int draftVersionNo, String draftState, String draftDefinition, String sentBackReason,
                   Instant submittedAt, UUID submittedBy, long revision, Instant updatedAt, UUID updatedBy,
                   String requestHash) {
    }

    record VersionRow(UUID id, UUID formId, int versionNo, String definition, UUID approvedBy, Instant approvedAt,
                      Instant publishedAt, UUID publishedBy) {
    }

    record StarterRow(String starterKey, String name, String description, String suitedFor, String definition) {
    }

    record NewForm(String formKey, String name, String starterKey, int draftVersionNo, String draftDefinition,
                   String idempotencyKey, String requestHash) {
    }

    /** All mutable columns of a form after a change; the service computes the whole new state. */
    record FormState(String name, String status, int liveVersionNo, int draftVersionNo, String draftState,
                     String draftDefinition, String sentBackReason, Instant submittedAt, UUID submittedBy) {
    }

    List<FormRow> findAll(UUID tenantId);

    Optional<FormRow> find(UUID tenantId, String formKey);

    Optional<FormRow> findByIdempotencyKey(UUID tenantId, String idempotencyKey);

    int count(UUID tenantId);

    /** Case-insensitive name check across the clinic's forms, leaving out one form (null = none). */
    boolean nameTaken(UUID tenantId, String name, UUID exceptFormId);

    /** Inserts a form in state DRAFT; a duplicate key, name or idempotency key is a {@code DuplicateKeyException}. */
    UUID insert(UUID tenantId, NewForm form, UUID actorUserId);

    /** Compare-and-set on the revision: writes the new state and revision + 1. False when the revision moved. */
    boolean update(UUID tenantId, UUID formId, long expectedRevision, FormState state, UUID actorUserId);

    UUID insertVersion(UUID tenantId, UUID formId, int versionNo, String definition, UUID approvedBy,
                       UUID publishedBy);

    List<VersionRow> versions(UUID tenantId, UUID formId);

    Optional<VersionRow> version(UUID tenantId, UUID formId, int versionNo);

    /** A live form with the definition of its live version (SAH-59 forms in layouts and records). */
    record LiveForm(String formKey, String name, int versionNo, String definition) {
    }

    /**
     * The ACTIVE forms with a live version whose settings use this department or this place (EMERGENCY, IPD_WARD);
     * either may be null. By name, at most the clinic's 100 forms.
     */
    List<LiveForm> liveFor(UUID tenantId, String departmentKey, String place);

    /** One immutable version of a form by the form key, with the form's current name. */
    Optional<LiveForm> formVersion(UUID tenantId, String formKey, int versionNo);

    List<StarterRow> starters();

    Optional<StarterRow> starter(String starterKey);
}
