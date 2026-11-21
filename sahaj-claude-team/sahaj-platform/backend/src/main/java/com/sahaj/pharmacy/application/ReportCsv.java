package com.sahaj.pharmacy.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * The CSV of a report (docs/api/pharmacy.yaml 0.3.6, ADR-028 R5-7): UTF-8 with a byte order mark, comma separated, CRLF line
 * ends, a header row with the screen's column names. Money and rates are plain numbers with two decimals, dates read 24 Oct 2026
 * and times 11:05 AM IST. A text cell that starts with =, +, -, @, tab or carriage return gets a single quote in front, so a
 * spreadsheet never runs it as a formula.
 */
public final class ReportCsv {

    /** The byte order mark that makes a spreadsheet read the file as UTF-8. */
    public static final String BOM = "﻿";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("d MMM yyyy h:mm a", Locale.ENGLISH);

    private ReportCsv() {
    }

    /** The whole file: the header, then one line per row (cells already formatted by {@link #cell}). */
    public static byte[] write(List<String> header, List<List<String>> rows) {
        StringBuilder out = new StringBuilder(BOM);
        line(out, header);
        for (List<String> row : rows) {
            line(out, row);
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void line(StringBuilder out, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(escape(cells.get(i)));
        }
        out.append("\r\n");
    }

    /** A text cell that would start a formula gets a single quote in front (numbers never pass through here). */
    public static String neutralise(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        char first = text.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
            return "'" + text;
        }
        return text;
    }

    /** Quotes the value when it holds a comma, quote or line break. */
    public static String escape(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    /** One cell as the file shows it. A null is an empty cell. Numbers are never given a quote prefix. */
    public static String cell(Object value, ReportDef.Kind kind) {
        if (value == null) {
            return "";
        }
        switch (kind) {
            case PAISE:
                return value instanceof Number n ? paise(n.longValue()) : value.toString();
            case RATE:
            case PERCENT:
                return value instanceof Number n ? twoDecimals(new BigDecimal(n.toString())) : value.toString();
            case INTEGER:
                return value.toString();
            case DATE:
                if (value instanceof LocalDate date) {
                    return DAY.format(date);
                }
                if (value instanceof Instant instant) {
                    return DAY.format(instant.atZone(PharmacyTime.IST));
                }
                return value.toString();
            case DATE_TIME:
                if (value instanceof Instant instant) {
                    return time(instant);
                }
                return value.toString();
            case TEXT:
            default:
                return neutralise(value.toString());
        }
    }

    /** 11:05 AM IST style: "24 Oct 2026 11:05 AM IST". */
    public static String time(Instant instant) {
        ZonedDateTime zoned = instant.atZone(PharmacyTime.IST);
        return TIME.format(zoned).replace("am", "AM").replace("pm", "PM") + " IST";
    }

    /** Paise as rupees with two decimals, no symbol and no grouping: 12550 is 125.50. */
    public static String paise(long paise) {
        return BigDecimal.valueOf(paise).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    private static String twoDecimals(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
