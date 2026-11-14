package com.sahaj.opd.application.emr;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Port over the clinic's EMR departments, their immutable published versions and their doctors (ADR-020 D2, D5, D6).
 * Row level security limits every statement to the bound clinic and the tenant id is also in each statement. A
 * department row is created lazily (the 15 starter departments exist virtually until the first save, OQ-45-11).
 * Versions are insert-only. The documents are JSON text (see {@link EmrLayoutJson}); draft documents hold settings only.
 */
public interface EmrDepartmentRepository {

    record DepartmentRow(UUID id, String key, String name, String initials, String colour, String starterKey,
                         String sourceKind, String copiedFromKey, List<String> rooms, String draftLayout,
                         int publishedVersion, long revision) {
    }

    record VersionRow(UUID id, UUID departmentId, int versionNo, String layout, Instant publishedAt, UUID publishedBy) {
    }

    /** What an insert needs; revision starts at the given value (0 for a first save of a starter, 1 when created). */
    record NewDepartment(String key, String name, String initials, String colour, String starterKey,
                         String sourceKind, String copiedFromKey, List<String> rooms, String draftLayout,
                         long revision) {
    }

    List<DepartmentRow> findAll(UUID tenantId);

    Optional<DepartmentRow> find(UUID tenantId, String departmentKey);

    /** The department row locked until the end of the transaction (publish). */
    Optional<DepartmentRow> findForUpdate(UUID tenantId, String departmentKey);

    /** Departments whose key is not one of the starters. */
    int countAdded(UUID tenantId, List<String> starterKeys);

    /** Case-insensitive name check across the clinic's rows. */
    boolean nameTaken(UUID tenantId, String name);

    /** Inserts the row; a duplicate key or name is a {@code DuplicateKeyException}. Returns the new id. */
    UUID insert(UUID tenantId, NewDepartment department, UUID actorUserId);

    /**
     * Inserts the row unless its key or its name (any case) is already taken, without raising an error: a unique
     * violation would abort the whole transaction, so the caller could not ask which of the two it was. Empty when
     * nothing was inserted. Two requests with the same name at once: the second waits for the first, then gets empty.
     */
    Optional<UUID> insertIfAbsent(UUID tenantId, NewDepartment department, UUID actorUserId);

    /** Compare-and-set on the revision: sets the draft and revision + 1. False when the revision moved. */
    boolean saveDraft(UUID tenantId, UUID departmentId, long expectedRevision, String draftLayout, UUID actorUserId);

    /** Compare-and-set on the revision: sets published_version, clears the draft, revision + 1. */
    boolean markPublished(UUID tenantId, UUID departmentId, long expectedRevision, int versionNo, UUID actorUserId);

    UUID insertVersion(UUID tenantId, UUID departmentId, int versionNo, String layout, UUID actorUserId);

    Optional<VersionRow> latestVersion(UUID tenantId, UUID departmentId);

    Optional<VersionRow> version(UUID tenantId, UUID departmentId, int versionNo);

    Optional<VersionRow> versionById(UUID tenantId, UUID versionId);

    /** The latest version of every department that has one, keyed by department id. */
    Map<UUID, VersionRow> latestVersions(UUID tenantId);

    void insertDoctor(UUID tenantId, UUID departmentId, UUID doctorMembershipId, UUID actorUserId);

    /** Doctor membership ids per department id. */
    Map<UUID, Set<UUID>> doctorsByDepartment(UUID tenantId);

    /** Published departments (at least one version) this doctor is assigned to, by name. */
    List<DepartmentRow> publishedForDoctor(UUID tenantId, UUID doctorMembershipId);
}
