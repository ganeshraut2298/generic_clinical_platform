package com.sahaj.ipd.infrastructure.persistence;

import com.sahaj.ipd.application.AdmissionRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code ipd.admission}, {@code ipd.deposit} and {@code ipd.ip_sequence} (SAH-23). Insert statements list only
 * the columns the database role may write; ids and times come from the database defaults.
 */
@Repository
public class JdbcAdmissionRepository implements AdmissionRepository {

    private static final String NEXT_IP_NO = """
            insert into ipd.ip_sequence (tenant_id, fy, last_no)
            values (:tenantId, :fy, 1)
            on conflict (tenant_id, fy) do update set last_no = ip_sequence.last_no + 1
            returning last_no
            """;
    private static final String EXISTS_ADMITTED = """
            select exists (
                select 1 from ipd.admission
                where tenant_id = :tenantId and patient_id = :patientId and status = 'ADMITTED')
            """;
    private static final String FIND_ADMITTED_OF = """
            select id, ip_number from ipd.admission
            where tenant_id = :tenantId and patient_id = :patientId and status = 'ADMITTED' limit 1
            """;
    private static final String INSERT = """
            insert into ipd.admission (patient_id, ip_number, bed_id, doctor_membership_id, admission_type, source,
                                       payer, reason, consent_signed, status, admitted_by, treatment_course_id,
                                       treatment_course_sitting_id, admission_request_id)
            values (:patientId, :ipNumber, :bedId, :doctor, :type, :source, :payer, :reason, true, 'ADMITTED', :by,
                    :courseId, :sittingId, :requestId)
            returning id
            """;
    private static final String INSERT_COURSE_DEPOSIT = """
            insert into ipd.deposit (admission_id, amount_paise, mode, collected_by, source, course_credit_transfer_id)
            values (:admissionId, :amount, null, :by, 'COURSE', :transferId)
            returning id
            """;
    private static final String COURSE_LINK = """
            select treatment_course_id, treatment_course_sitting_id from ipd.admission
            where tenant_id = :tenantId and id = :id and treatment_course_id is not null
            """;
    private static final String INSERT_DEPOSIT = """
            insert into ipd.deposit (admission_id, amount_paise, mode, collected_by)
            values (:admissionId, :amount, :mode, :by)
            returning id
            """;
    // The admission row is written once; the list with placement (SAH-36) is built from the same pieces, so the two
    // reads cannot drift apart (code review R36-8).
    private static final String ADMISSION_COLUMNS = """
            select a.id, a.ip_number, a.patient_id, a.bed_id, b.code as bed_code, b.category_key,
                   w.name as ward_name, a.doctor_membership_id, a.admission_type, a.payer, a.reason, a.status,
                   a.admitted_at, a.discharged_at, a.package_name, a.package_price_paise, a.package_days,
                   coalesce((select sum(d.amount_paise) from ipd.deposit d
                             where d.tenant_id = a.tenant_id and d.admission_id = a.id), 0) as deposit_total""";
    private static final String ADMISSION_FROM = """
            from ipd.admission a
            join ipd.bed b on b.tenant_id = a.tenant_id and b.id = a.bed_id
            join ipd.ward w on w.tenant_id = b.tenant_id and w.id = b.ward_id
            """;
    private static final String SELECT = ADMISSION_COLUMNS + "\n" + ADMISSION_FROM;
    private static final String ADMITTED_FILTER = """
            where a.tenant_id = :tenantId and a.status = 'ADMITTED'
            order by a.admitted_at desc, a.id
            """;
    // SAH-36 (ADR-013 R1): the same row plus the ward id and the room name of the bed (left join: a bed may have no room).
    static final String FIND_ADMITTED_WITH_PLACEMENT = ADMISSION_COLUMNS
            + ",\n       b.ward_id as ward_id, r.name as room_name\n" + ADMISSION_FROM
            + "left join ipd.room r on r.tenant_id = b.tenant_id and r.id = b.room_id\n" + ADMITTED_FILTER;
    static final String FIND_ADMITTED = SELECT + ADMITTED_FILTER;
    private static final String COUNT_ADMITTED = """
            select count(*) from ipd.admission where tenant_id = :tenantId and status = 'ADMITTED'
            """;
    private static final String FIND = SELECT + """
            where a.tenant_id = :tenantId and a.id = :id
            """;
    // Review M2 (ADR-006 D4): the lock is taken on the bare admission row first and the joined row is read in a
    // second statement. In READ COMMITTED a statement that waited on a row lock re-checks only the locked row, with
    // the joined bed and ward rows as of its own snapshot, so a wait behind a transfer would otherwise fail the join.
    private static final String LOCK_FOR_UPDATE = """
            select id, status from ipd.admission
            where tenant_id = :tenantId and id = :id
            for update
            """;
    private static final String LOCK_FOR_NO_KEY_UPDATE = """
            select id, status from ipd.admission
            where tenant_id = :tenantId and id = :id
            for no key update
            """;
    private static final String UPDATE_BED = """
            update ipd.admission
            set bed_id = :bedId, version = version + 1
            where tenant_id = :tenantId and id = :id and status = 'ADMITTED'
            """;
    private static final String MARK_DISCHARGED = """
            update ipd.admission
            set status = 'DISCHARGED', discharged_at = :at, version = version + 1
            where tenant_id = :tenantId and id = :id and status = 'ADMITTED'
            """;
    private static final String FIND_DEPOSITS = """
            select id, amount_paise, mode, collected_at, collected_by, source
            from ipd.deposit
            where tenant_id = :tenantId and admission_id = :admissionId
            order by collected_at, id
            """;

    private final JdbcClient jdbc;

    public JdbcAdmissionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int nextIpNo(UUID tenantId, String fy) {
        return jdbc.sql(NEXT_IP_NO).param("tenantId", tenantId).param("fy", fy)
                .query((rs, rowNum) -> rs.getInt(1)).single();
    }

    @Override
    public boolean existsAdmitted(UUID tenantId, UUID patientId) {
        return jdbc.sql(EXISTS_ADMITTED).param("tenantId", tenantId).param("patientId", patientId)
                .query((rs, rowNum) -> rs.getBoolean(1)).single();
    }

    @Override
    public UUID insert(UUID tenantId, NewAdmission a) {
        return jdbc.sql(INSERT).param("patientId", a.patientId()).param("ipNumber", a.ipNumber())
                .param("bedId", a.bedId()).param("doctor", a.doctorMembershipId()).param("type", a.admissionType())
                .param("source", a.source()).param("payer", a.payer()).param("reason", a.reason())
                .param("by", a.admittedBy()).param("courseId", a.treatmentCourseId())
                .param("sittingId", a.treatmentCourseSittingId()).param("requestId", a.admissionRequestId())
                .query((rs, rowNum) -> rs.getObject(1, UUID.class)).single();
    }

    @Override
    public Optional<AdmittedRef> findAdmittedOf(UUID tenantId, UUID patientId) {
        return jdbc.sql(FIND_ADMITTED_OF).param("tenantId", tenantId).param("patientId", patientId)
                .query((rs, rowNum) -> new AdmittedRef(rs.getObject("id", UUID.class), rs.getString("ip_number")))
                .optional();
    }

    @Override
    public UUID insertDeposit(UUID tenantId, UUID admissionId, long amountPaise, String mode, UUID collectedBy) {
        return jdbc.sql(INSERT_DEPOSIT).param("admissionId", admissionId).param("amount", amountPaise)
                .param("mode", mode).param("by", collectedBy)
                .query((rs, rowNum) -> rs.getObject(1, UUID.class)).single();
    }

    @Override
    public List<AdmissionRow> findAdmitted(UUID tenantId) {
        return jdbc.sql(FIND_ADMITTED).param("tenantId", tenantId).query(JdbcAdmissionRepository::admission).list();
    }

    @Override
    public List<AdmittedRow> findAdmittedWithPlacement(UUID tenantId) {
        return jdbc.sql(FIND_ADMITTED_WITH_PLACEMENT).param("tenantId", tenantId)
                .query((rs, rowNum) -> new AdmittedRow(admission(rs, rowNum), rs.getObject("ward_id", UUID.class),
                        rs.getString("room_name")))
                .list();
    }

    @Override
    public int countAdmitted(UUID tenantId) {
        return jdbc.sql(COUNT_ADMITTED).param("tenantId", tenantId).query((rs, rowNum) -> rs.getInt(1)).single();
    }

    private static final String BY_PATIENT = SELECT + """
            where a.tenant_id = :tenantId and a.patient_id = :patientId
            order by a.admitted_at desc, a.id limit :limit
            """;

    @Override
    public UUID insertCourseDeposit(UUID tenantId, UUID admissionId, long amountPaise, UUID transferId,
                                    UUID collectedBy) {
        return jdbc.sql(INSERT_COURSE_DEPOSIT).param("admissionId", admissionId).param("amount", amountPaise)
                .param("transferId", transferId).param("by", collectedBy)
                .query((rs, rowNum) -> rs.getObject(1, UUID.class)).single();
    }

    @Override
    public Optional<CourseLink> findCourseLink(UUID tenantId, UUID admissionId) {
        return jdbc.sql(COURSE_LINK).param("tenantId", tenantId).param("id", admissionId)
                .query((rs, rowNum) -> new CourseLink(rs.getObject("treatment_course_id", UUID.class),
                        rs.getObject("treatment_course_sitting_id", UUID.class))).optional();
    }

    @Override
    public List<AdmissionRow> findByPatient(UUID tenantId, UUID patientId, int limit) {
        return jdbc.sql(BY_PATIENT).param("tenantId", tenantId).param("patientId", patientId).param("limit", limit)
                .query(JdbcAdmissionRepository::admission).list();
    }

    @Override
    public Optional<AdmissionRow> find(UUID tenantId, UUID admissionId) {
        return jdbc.sql(FIND).param("tenantId", tenantId).param("id", admissionId)
                .query(JdbcAdmissionRepository::admission).optional();
    }

    @Override
    public Optional<AdmissionRow> findForUpdate(UUID tenantId, UUID admissionId) {
        return lockThenRead(LOCK_FOR_UPDATE, tenantId, admissionId);
    }

    @Override
    public Optional<AdmissionRow> findForNoKeyUpdate(UUID tenantId, UUID admissionId) {
        return lockThenRead(LOCK_FOR_NO_KEY_UPDATE, tenantId, admissionId);
    }

    /** Locks the bare admission row (empty when missing or hidden by row-level security), then reads the joined row. */
    private Optional<AdmissionRow> lockThenRead(String lockSql, UUID tenantId, UUID admissionId) {
        boolean locked = jdbc.sql(lockSql).param("tenantId", tenantId).param("id", admissionId)
                .query((rs, rowNum) -> rs.getObject("id", UUID.class)).optional().isPresent();
        return locked ? find(tenantId, admissionId) : Optional.empty();
    }

    @Override
    public void updateBed(UUID tenantId, UUID admissionId, UUID bedId) {
        // Security review M1: a move changes one ADMITTED admission, nothing else. The database trigger is the
        // last line of defence; anything but exactly one row here means the service state is wrong.
        int updated = jdbc.sql(UPDATE_BED).param("tenantId", tenantId).param("id", admissionId)
                .param("bedId", bedId).update();
        if (updated != 1) {
            throw new IllegalStateException("The bed of the admission was not changed");
        }
    }

    @Override
    public boolean markDischarged(UUID tenantId, UUID admissionId, Instant dischargedAt) {
        return jdbc.sql(MARK_DISCHARGED).param("tenantId", tenantId).param("id", admissionId)
                .param("at", dischargedAt).update() > 0;
    }

    @Override
    public List<DepositRow> findDeposits(UUID tenantId, UUID admissionId) {
        return jdbc.sql(FIND_DEPOSITS).param("tenantId", tenantId).param("admissionId", admissionId)
                .query((rs, rowNum) -> new DepositRow(rs.getObject("id", UUID.class), rs.getLong("amount_paise"),
                        rs.getString("mode"), rs.getObject("collected_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("collected_by", UUID.class), rs.getString("source")))
                .list();
    }

    private static AdmissionRow admission(ResultSet rs, int rowNum) throws SQLException {
        return new AdmissionRow(rs.getObject("id", UUID.class), rs.getString("ip_number"),
                rs.getObject("patient_id", UUID.class), rs.getObject("bed_id", UUID.class), rs.getString("bed_code"),
                rs.getString("category_key"), rs.getString("ward_name"),
                rs.getObject("doctor_membership_id", UUID.class), rs.getString("admission_type"),
                rs.getString("payer"), rs.getString("reason"), rs.getString("status"),
                rs.getObject("admitted_at", OffsetDateTime.class).toInstant(), rs.getLong("deposit_total"),
                rs.getObject("discharged_at", OffsetDateTime.class) == null ? null
                        : rs.getObject("discharged_at", OffsetDateTime.class).toInstant(),
                packageCopy(rs));
    }

    /** SAH-29: the three package columns are all set or all null (a database check); null when not set. */
    private static PackageCopy packageCopy(ResultSet rs) throws SQLException {
        String name = rs.getString("package_name");
        if (name == null) {
            return null;
        }
        return new PackageCopy(name, rs.getLong("package_price_paise"), rs.getInt("package_days"));
    }
}
