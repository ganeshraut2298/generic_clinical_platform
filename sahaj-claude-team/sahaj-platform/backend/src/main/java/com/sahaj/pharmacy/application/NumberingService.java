package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.DocumentSeriesRepository.Row;
import com.sahaj.pharmacy.application.PharmacyMasterInputs.SeriesSave;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.DocType;
import com.sahaj.pharmacy.domain.DocumentNumbers;
import com.sahaj.pharmacy.domain.Unit;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-78-10, numbering (docs/api/pharmacy.yaml 0.2.0, ADR-028 D8, ADR-029 P9). One series per unit and document type; until a
 * series is saved its defaults are served (saved false, version 0). The change applies to numbers taken after the save:
 * issued numbers are never changed, reused or skipped. This service issues no number; the race-safe next-number function of
 * the database is for SAH-79. {@code numbersIssued} is true once a number of the type was taken in the current financial year
 * (1 April to 31 March, IST), the same check as the series lock: it turns false again on 1 April, when the series can be
 * changed again (contract 0.3.1).
 */
@Service
public class NumberingService {

    static final String PREFIX_MESSAGE = "Use 1 to 6 letters, digits, - or /";
    static final String TOO_LONG = "The number must be 16 characters or fewer";
    static final String SERIES_LOCKED = "Numbers have already been issued this year.";
    static final String RESTART_NEEDS_YEAR = "Show the financial year when numbers restart every 1 April";
    private static final String ENTITY_TYPE = "PHARMACY_DOCUMENT_SERIES";

    private final PharmacyGate gate;
    private final UnitService units;
    private final DocumentSeriesRepository series;
    private final AuditLog auditLog;
    private final PharmacyTime time;
    private final StoreBillSeriesRepository storeSeries;

    public NumberingService(PharmacyGate gate, UnitService units, DocumentSeriesRepository series, AuditLog auditLog,
                            PharmacyTime time, StoreBillSeriesRepository storeSeries) {
        this.gate = gate;
        this.units = units;
        this.series = series;
        this.auditLog = auditLog;
        this.time = time;
        this.storeSeries = storeSeries;
    }

    /** Ten rows in the order of {@link DocType}; the defaults for a type that was never saved. Writes nothing. */
    @Transactional(readOnly = true)
    public PharmacyMasterViews.DocumentSeriesList list(UUID tenantId, UUID userId) {
        gate.require(tenantId, userId, AccessLevel.SETUP_VIEW);
        return new PharmacyMasterViews.DocumentSeriesList(views(tenantId));
    }

    /** For tests: the body is already read. */
    PharmacyMasterViews.DocumentSeries save(UUID tenantId, UUID userId, String rawDocType, SeriesSave input) {
        return save(tenantId, userId, rawDocType, PharmacyInputs.Body.of(input));
    }

    /** Owner only. An unknown document type is 400 bad-request, read before the body. */
    @Transactional
    public PharmacyMasterViews.DocumentSeries save(UUID tenantId, UUID userId, String rawDocType,
                                                   PharmacyInputs.Body<SeriesSave> body) {
        gate.require(tenantId, userId, AccessLevel.SETUP_EDIT);
        DocType type = docType(rawDocType);
        SeriesSave in = body.read();
        Checked checked = check(in);

        Unit unit = units.ensureUnit(tenantId, userId);
        // Lock order (same as StoreBillSeriesService.put): the unit BILL series row first, then the document type's own row.
        // A store series save and a unit series save therefore cannot both pass the prefix check at once.
        if (type != DocType.BILL) {
            series.findForUpdate(tenantId, DocType.BILL);
        }
        Row row = series.findForUpdate(tenantId, type).orElse(null);
        long currentVersion = row == null ? 0L : row.version();
        if (checked.version() != currentVersion) {
            throw PharmacyProblemException.settingsChanged(view(type, row, issuedThisYear(tenantId), time.today()));
        }
        String prefix = checked.prefix();
        List<Row> saved = series.list(tenantId);
        for (DocType other : DocType.values()) {
            if (other != type && effectivePrefix(other, saved).equalsIgnoreCase(prefix)) {
                throw new ConflictException("prefix-in-use", "Prefix already used",
                        "Two document types cannot use the same prefix");
            }
        }
        for (StoreBillSeriesRepository.Row own : storeSeries.list(tenantId)) {
            if (own.prefix().equalsIgnoreCase(prefix)) {
                throw new ConflictException("prefix-in-use", "Prefix already used",
                        "Two document types cannot use the same prefix");
            }
        }
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        Settings was = row == null ? Settings.defaults(type)
                : new Settings(row.prefix(), row.financialYearPart(), row.sequenceLength(), row.restartsEachFinancialYear());
        Settings now = new Settings(prefix, checked.financialYearPart(), checked.sequenceLength(), checked.restarts());
        track(before, after, "prefix", was.prefix(), now.prefix());
        track(before, after, "financialYearPart", was.financialYearPart(), now.financialYearPart());
        track(before, after, "sequenceLength", was.sequenceLength(), now.sequenceLength());
        track(before, after, "restartsEachFinancialYear", was.restarts(), now.restarts());
        if (row != null && before.isEmpty()) {
            return view(type, row, issuedThisYear(tenantId), time.today());
        }
        if (series.issuedSince(tenantId, type, time.financialYearStart())) {
            throw new ConflictException("series-locked", "Series locked", SERIES_LOCKED);
        }
        UUID id;
        if (row == null) {
            id = series.insert(tenantId, unit.id(), type, prefix, now.financialYearPart(), now.sequenceLength(),
                    now.restarts(), userId);
        } else {
            id = row.id();
            if (!series.update(tenantId, id, row.version(), prefix, now.financialYearPart(), now.sequenceLength(),
                    now.restarts(), userId)) {
                throw PharmacyProblemException.settingsChanged(view(type, series.find(tenantId, type).orElse(row),
                        issuedThisYear(tenantId), time.today()));
            }
        }
        before.put("docType", type.name());
        after.put("docType", type.name());
        auditLog.record("PHARMACY_SERIES_CHANGED", ENTITY_TYPE, id, before, after);
        return view(type, series.find(tenantId, type).orElseThrow(), issuedThisYear(tenantId), time.today());
    }

    // ---- views ----

    private Set<DocType> issuedThisYear(UUID tenantId) {
        return series.issuedSince(tenantId, time.financialYearStart());
    }

    private List<PharmacyMasterViews.DocumentSeries> views(UUID tenantId) {
        Map<DocType, Row> byType = new EnumMap<>(DocType.class);
        series.list(tenantId).forEach(row -> byType.put(row.docType(), row));
        Set<DocType> issued = issuedThisYear(tenantId);
        LocalDate today = time.today();
        List<PharmacyMasterViews.DocumentSeries> items = new ArrayList<>();
        for (DocType type : DocType.values()) {
            items.add(view(type, byType.get(type), issued, today));
        }
        return items;
    }

    static PharmacyMasterViews.DocumentSeries view(DocType type, Row row, Set<DocType> issued, LocalDate today) {
        Settings s = row == null ? Settings.defaults(type)
                : new Settings(row.prefix(), row.financialYearPart(), row.sequenceLength(), row.restartsEachFinancialYear());
        String example = DocumentNumbers.render(s.prefix(), s.financialYearPart(), s.sequenceLength(), 1L, today);
        return new PharmacyMasterViews.DocumentSeries(type.name(), s.prefix(), s.financialYearPart(), s.sequenceLength(),
                s.restarts(), example, example.length(), issued.contains(type), row != null, row == null ? 0L : row.version());
    }

    private static String effectivePrefix(DocType type, List<Row> saved) {
        return saved.stream().filter(row -> row.docType() == type).map(Row::prefix).findFirst()
                .orElse(type.defaultPrefix()).toLowerCase(Locale.ROOT);
    }

    private record Settings(String prefix, boolean financialYearPart, int sequenceLength, boolean restarts) {

        static Settings defaults(DocType type) {
            return new Settings(type.defaultPrefix(), type.defaultFinancialYearPart(), type.defaultSequenceLength(),
                    type.defaultRestarts());
        }
    }

    // ---- checks ----

    private record Checked(long version, String prefix, boolean financialYearPart, int sequenceLength, boolean restarts) {
    }

    private static DocType docType(String raw) {
        if (raw != null) {
            for (DocType type : DocType.values()) {
                if (type.name().equals(raw)) {
                    return type;
                }
            }
        }
        throw PharmacyProblemException.badRequest();
    }

    /** All field errors together (400 validation); nothing typed is echoed back. */
    private static Checked check(SeriesSave in) {
        List<FieldMessage> errors = new ArrayList<>();
        if (in.version() == null || in.version() < 0) {
            errors.add(new FieldMessage("version", UnitService.RELOAD));
        }
        String prefix = in.prefix();
        boolean prefixOk = prefix != null && DocumentNumbers.PREFIX.matcher(prefix).matches();
        if (!prefixOk) {
            errors.add(new FieldMessage("prefix", PREFIX_MESSAGE));
        }
        Integer length = PharmacyParams.wholeNumber(in.sequenceLength(), DocumentNumbers.MIN_SEQUENCE,
                DocumentNumbers.MAX_SEQUENCE);
        if (length == null) {
            errors.add(new FieldMessage("sequenceLength", "Enter a length from 4 to 8"));
        }
        if (in.financialYearPart() == null) {
            errors.add(new FieldMessage("financialYearPart", UnitService.REQUIRED));
        }
        if (in.restartsEachFinancialYear() == null) {
            errors.add(new FieldMessage("restartsEachFinancialYear", UnitService.REQUIRED));
        }
        boolean fy = Boolean.TRUE.equals(in.financialYearPart());
        boolean restarts = Boolean.TRUE.equals(in.restartsEachFinancialYear());
        if (restarts && !fy && in.financialYearPart() != null) {
            errors.add(new FieldMessage("restartsEachFinancialYear", RESTART_NEEDS_YEAR));
        }
        if (prefixOk && length != null && DocumentNumbers.renderedLength(prefix, fy, length) > DocumentNumbers.MAX_LENGTH) {
            errors.add(new FieldMessage("sequenceLength", TOO_LONG));
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        return new Checked(in.version(), prefix, fy, length, restarts);
    }

    private static void track(Map<String, Object> before, Map<String, Object> after, String field, Object was, Object now) {
        if (!was.equals(now)) {
            before.put(field, was.toString());
            after.put(field, now.toString());
        }
    }
}
