package com.sahaj.pharmacy.infrastructure;

import com.sahaj.pharmacy.application.ReportDef;
import com.sahaj.pharmacy.application.ReportRepository;
import com.sahaj.pharmacy.domain.StockState;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.stereotype.Repository;

/**
 * JDBC over the SAH-79 tables for the reports (SAH-79-18 and 79-19). Every query selects {@code store_id} and {@code product_id}
 * first and then one column per report column, in order; {@code count} wraps the same query, so the page and the total always
 * agree. Dates are IST days: a timestamp is compared with the start of the first day and the start of the day after the last.
 * Nothing here selects a patient name, mobile or ABHA number, and nothing is logged.
 */
@Repository
public class JdbcReportRepository implements ReportRepository {

    private static final String PLABEL = "trim(coalesce(g.name, p.name) || ' ' || coalesce(coalesce(g.strength, p.strength), ''))";
    private static final String PJOIN = """
              join pharmacy.product p on p.tenant_id = %1$s.tenant_id and p.id = %1$s.product_id
              left join pharmacy.catalogue_product g on g.id = p.catalogue_product_id
            """;

    private static String productJoin(String alias) {
        return PJOIN.formatted(alias);
    }

    private static String range(String column) {
        return "(cast(:from as date) is null or " + column + " >= (cast(:from as date)::timestamp at time zone 'Asia/Kolkata'))"
                + " and (cast(:to as date) is null or " + column
                + " < ((cast(:to as date) + 1)::timestamp at time zone 'Asia/Kolkata'))";
    }

    private static final String PRODUCT_FILTER = " and (cast(:productId as uuid) is null or %s.product_id = cast(:productId as uuid))";

    private static final String STORE_STOCK = "select sb.store_id, sb.product_id, s.name, " + PLABEL + ","
            + " coalesce(sum(case when sb.state = 'AVAILABLE' then sb.qty end), 0)::bigint,"
            + " coalesce(sum(case when sb.state = 'IN_TRANSIT' then sb.qty end), 0)::bigint,"
            + " coalesce(sum(case when sb.state = 'PENDING_RETURN' then sb.qty end), 0)::bigint,"
            + " coalesce(sum(case when sb.state = 'QUARANTINE' then sb.qty end), 0)::bigint,"
            + " (select st.reorder_level from pharmacy.product_store_setting st where st.tenant_id = sb.tenant_id"
            + "   and st.product_id = sb.product_id and st.store_id = sb.store_id)::bigint,"
            + " coalesce(round(sum(sb.qty * b.purchase_rate) * 100), 0)::bigint"
            + " from pharmacy.stock_balance sb"
            + " join pharmacy.store s on s.tenant_id = sb.tenant_id and s.id = sb.store_id"
            + " join pharmacy.batch b on b.tenant_id = sb.tenant_id and b.id = sb.batch_id" + productJoin("sb")
            + " where sb.tenant_id = :tenantId and sb.store_id in (:storeIds) and sb.qty > 0"
            + PRODUCT_FILTER.formatted("sb")
            + " group by sb.tenant_id, sb.store_id, sb.product_id, s.name, p.id, g.id"
            + " order by lower(s.name), lower(" + PLABEL + "), sb.product_id";

    private static final String BATCH_EXPIRY = "select sb.store_id, sb.product_id, s.name, " + PLABEL + ", b.batch_no,"
            + " b.expiry_date, sb.state, sb.qty::bigint, round(b.mrp * 100)::bigint, round(b.purchase_rate * 100)::bigint,"
            + " round(b.purchase_rate * 100)::bigint,"
            + " case when b.mrp > 0 and b.purchase_rate is not null then round((b.mrp - b.purchase_rate) * 100 / b.mrp, 2) end,"
            + " v.name,"
            + " case when b.expiry_date < :today then 'Expired' when b.expiry_date <= :today + 90 then 'Near expiry' else 'Saleable' end"
            + " from pharmacy.stock_balance sb"
            + " join pharmacy.store s on s.tenant_id = sb.tenant_id and s.id = sb.store_id"
            + " join pharmacy.batch b on b.tenant_id = sb.tenant_id and b.id = sb.batch_id" + productJoin("sb")
            + " left join pharmacy.vendor v on v.tenant_id = b.tenant_id and v.id = b.vendor_id"
            + " where sb.tenant_id = :tenantId and sb.store_id in (:storeIds) and sb.qty > 0"
            + PRODUCT_FILTER.formatted("sb")
            + " order by lower(s.name), lower(" + PLABEL + "), b.expiry_date, b.batch_no, sb.state, sb.id";

    private static final String BELOW_REORDER = "select s.store_id, s.product_id, st.name, " + PLABEL + ","
            + " coalesce(sum(case when b.id is not null then sb.qty end), 0)::bigint, s.reorder_level::bigint"
            + " from pharmacy.product_store_setting s"
            + " join pharmacy.store st on st.tenant_id = s.tenant_id and st.id = s.store_id"
            + " join pharmacy.product p on p.tenant_id = s.tenant_id and p.id = s.product_id and p.status = 'ACTIVE'"
            + " left join pharmacy.catalogue_product g on g.id = p.catalogue_product_id"
            + " left join pharmacy.stock_balance sb on sb.tenant_id = s.tenant_id and sb.store_id = s.store_id"
            + "   and sb.product_id = s.product_id and sb.state = 'AVAILABLE'"
            + " left join pharmacy.batch b on b.tenant_id = sb.tenant_id and b.id = sb.batch_id and b.expiry_date >= :today"
            + " where s.tenant_id = :tenantId and s.store_id in (:storeIds) and s.reorder_level is not null"
            + PRODUCT_FILTER.formatted("s")
            + " group by s.id, st.id, p.id, g.id"
            + " having coalesce(sum(case when b.id is not null then sb.qty end), 0) <= s.reorder_level"
            + " order by lower(st.name), lower(" + PLABEL + "), s.product_id";

    /** The dispatch sums of a document: dispatched, accepted, rejected, short and back-ordered units. */
    private static String dispatchSums(String docColumn, String idExpr) {
        return "(select coalesce(sum(dl.dispatched_qty), 0) from pharmacy.dispatch d join pharmacy.dispatch_line dl"
                + " on dl.tenant_id = d.tenant_id and dl.dispatch_id = d.id where d.tenant_id = x.tenant_id and d." + docColumn
                + " = " + idExpr + ")::bigint,"
                + " (select coalesce(sum(rl.accepted_qty), 0) from pharmacy.dispatch d join pharmacy.dispatch_receipt_line rl"
                + " on rl.tenant_id = d.tenant_id and rl.dispatch_id = d.id where d.tenant_id = x.tenant_id and d." + docColumn
                + " = " + idExpr + ")::bigint,"
                + " (select coalesce(sum(rl.rejected_qty), 0) from pharmacy.dispatch d join pharmacy.dispatch_receipt_line rl"
                + " on rl.tenant_id = d.tenant_id and rl.dispatch_id = d.id where d.tenant_id = x.tenant_id and d." + docColumn
                + " = " + idExpr + ")::bigint,"
                + " (select coalesce(sum(sh.qty), 0) from pharmacy.dispatch d join pharmacy.dispatch_line dl"
                + " on dl.tenant_id = d.tenant_id and dl.dispatch_id = d.id join pharmacy.shortage sh"
                + " on sh.tenant_id = dl.tenant_id and sh.dispatch_line_id = dl.id where d.tenant_id = x.tenant_id and d."
                + docColumn + " = " + idExpr + ")::bigint,"
                + " (select coalesce(sum(dl.back_ordered_qty), 0) from pharmacy.dispatch d join pharmacy.dispatch_line dl"
                + " on dl.tenant_id = d.tenant_id and dl.dispatch_id = d.id where d.tenant_id = x.tenant_id and d." + docColumn
                + " = " + idExpr + ")::bigint";
    }

    private static final String INDENT_REGISTER = "select x.from_store_id, null::uuid, x.doc_no, fs.name, ts.name, x.priority,"
            + " x.created_by, x.raised_at, x.status,"
            + " (select coalesce(sum(il.approved_qty), 0) from pharmacy.indent_line il where il.tenant_id = x.tenant_id"
            + "   and il.indent_id = x.id and not il.removed)::bigint,"
            + " (select coalesce(sum(il.dispatched_qty), 0) from pharmacy.indent_line il where il.tenant_id = x.tenant_id"
            + "   and il.indent_id = x.id and not il.removed)::bigint,"
            + " (select coalesce(sum(il.received_qty), 0) from pharmacy.indent_line il where il.tenant_id = x.tenant_id"
            + "   and il.indent_id = x.id and not il.removed)::bigint,"
            + " (select coalesce(sum(il.rejected_qty), 0) from pharmacy.indent_line il where il.tenant_id = x.tenant_id"
            + "   and il.indent_id = x.id and not il.removed)::bigint,"
            + " (select coalesce(sum(il.short_qty), 0) from pharmacy.indent_line il where il.tenant_id = x.tenant_id"
            + "   and il.indent_id = x.id and not il.removed)::bigint,"
            + " (select coalesce(sum(dl.back_ordered_qty), 0) from pharmacy.dispatch d join pharmacy.dispatch_line dl"
            + "   on dl.tenant_id = d.tenant_id and dl.dispatch_id = d.id where d.tenant_id = x.tenant_id"
            + "   and d.indent_id = x.id)::bigint"
            + " from pharmacy.indent x"
            + " join pharmacy.store fs on fs.tenant_id = x.tenant_id and fs.id = x.from_store_id"
            + " join pharmacy.store ts on ts.tenant_id = x.tenant_id and ts.id = x.to_store_id"
            + " where x.tenant_id = :tenantId and x.raised_at is not null and x.status not in ('DRAFT', 'DISCARDED')"
            + " and (x.from_store_id in (:storeIds) or x.to_store_id in (:storeIds)) and " + range("x.raised_at")
            + " and (cast(:productId as uuid) is null or exists (select 1 from pharmacy.indent_line il where il.tenant_id = x.tenant_id"
            + "   and il.indent_id = x.id and not il.removed and il.product_id = cast(:productId as uuid)))"
            + " order by x.raised_at, x.id";

    private static final String TRANSFER_REGISTER = "select x.from_store_id, null::uuid, x.doc_no, fs.name, ts.name, x.reason_key,"
            + " x.created_by, x.created_at, x.status,"
            + " (select coalesce(sum(tl.quantity), 0) from pharmacy.transfer_line tl where tl.tenant_id = x.tenant_id"
            + "   and tl.transfer_id = x.id)::bigint, " + dispatchSums("transfer_id", "x.id")
            + " from pharmacy.transfer x"
            + " join pharmacy.store fs on fs.tenant_id = x.tenant_id and fs.id = x.from_store_id"
            + " join pharmacy.store ts on ts.tenant_id = x.tenant_id and ts.id = x.to_store_id"
            + " where x.tenant_id = :tenantId and (x.from_store_id in (:storeIds) or x.to_store_id in (:storeIds))"
            + " and " + range("x.created_at")
            + " and (cast(:productId as uuid) is null or exists (select 1 from pharmacy.transfer_line tl where tl.tenant_id = x.tenant_id"
            + "   and tl.transfer_id = x.id and tl.product_id = cast(:productId as uuid)))"
            + " order by x.created_at, x.id";

    private static final String WARD_CONSUMPTION = "select c.store_id, cl.product_id, c.recorded_at, s.name, " + PLABEL + ", b.batch_no,"
            + " cl.quantity::bigint, case c.consumption_type when 'PATIENT' then 'Patient' else 'General' end, c.purpose_key,"
            + " (select max(l.doc_no) from pharmacy.stock_ledger l where l.tenant_id = c.tenant_id and l.doc_type = 'CONSUMPTION'"
            + "   and l.doc_id = c.id and l.admission_id is not null), c.recorded_by,"
            + " case c.charge_status when 'CHARGE_PENDING' then 'Charge pending' when 'CHARGED' then 'Charged' else 'Not applicable' end"
            + " from pharmacy.consumption c"
            + " join pharmacy.consumption_line cl on cl.tenant_id = c.tenant_id and cl.consumption_id = c.id"
            + " join pharmacy.store s on s.tenant_id = c.tenant_id and s.id = c.store_id"
            + " join pharmacy.batch b on b.tenant_id = cl.tenant_id and b.id = cl.batch_id" + productJoin("cl")
            + " where c.tenant_id = :tenantId and c.store_id in (:storeIds) and " + range("c.recorded_at")
            + PRODUCT_FILTER.formatted("cl")
            + " order by c.recorded_at, c.id, cl.id";

    /** Return items, then write-offs and shortage settlements from the ledger (the ledger is the record of what was posted). */
    private static final String RETURNS_WRITE_OFFS = "select * from ("
            + " select ri.holding_store_id as store_id, ri.product_id, ri.raised_at as happened, ri.doc_no, case when ri.status = 'DISPUTED'"
            + "   then 'Disputed' when ri.item_type = 'DAMAGED' then 'Damage report' else 'Return to CS' end as type,"
            + "   s.name as store_name, " + PLABEL + " as product_name, b.batch_no, ri.qty::bigint as qty,"
            + "   coalesce(round(ri.qty * b.purchase_rate * 100), 0)::bigint as value_paise, ri.reason_key,"
            + "   ri.accepted_by as approver, ri.status"
            + "  from pharmacy.return_item ri"
            + "  join pharmacy.store s on s.tenant_id = ri.tenant_id and s.id = ri.holding_store_id"
            + "  join pharmacy.batch b on b.tenant_id = ri.tenant_id and b.id = ri.batch_id" + productJoin("ri")
            + "  where ri.tenant_id = :tenantId and ri.holding_store_id in (:storeIds)"
            + " union all"
            + " select l.store_id, l.product_id, l.occurred_at, l.doc_no, case l.txn_type when 'WRITE_OFF' then 'Write-off'"
            + "   else 'Shortage settlement' end, s.name, " + PLABEL + ", b.batch_no, l.qty_out::bigint,"
            + "   coalesce(round(l.qty_out * b.purchase_rate * 100), 0)::bigint, l.reason_key,"
            + "   l.approved_by_membership_id, 'Posted'"
            + "  from pharmacy.stock_ledger l"
            + "  join pharmacy.store s on s.tenant_id = l.tenant_id and s.id = l.store_id"
            + "  join pharmacy.batch b on b.tenant_id = l.tenant_id and b.id = l.batch_id" + productJoin("l")
            + "  where l.tenant_id = :tenantId and l.store_id in (:storeIds) and l.txn_type in ('WRITE_OFF', 'SHORTAGE_WRITE_OFF')"
            + "   and l.qty_out > 0) x"
            + " where " + range("x.happened") + PRODUCT_FILTER.formatted("x") + " order by x.happened, x.doc_no";

    private static final String PO_VS_GRN = "select x.store_id, pl.product_id, x.doc_no, v.name, " + PLABEL + ", pl.qty::bigint,"
            + " pl.received_qty::bigint, greatest(pl.qty - pl.received_qty, 0)::bigint,"
            + " case when x.status in ('SENT', 'PARTLY_RECEIVED') and x.delivery_expected < :today then 'OVERDUE' else x.status end,"
            + " x.delivery_expected"
            + " from pharmacy.purchase_order x"
            + " join pharmacy.purchase_order_line pl on pl.tenant_id = x.tenant_id and pl.purchase_order_id = x.id and not pl.removed"
            + " join pharmacy.vendor v on v.tenant_id = x.tenant_id and v.id = x.vendor_id" + productJoin("pl")
            + " where x.tenant_id = :tenantId and x.store_id in (:storeIds) and x.status not in ('DRAFT', 'DISCARDED')"
            + " and x.doc_no is not null and " + range("x.created_at")
            + PRODUCT_FILTER.formatted("pl")
            + " and (cast(:vendorId as uuid) is null or x.vendor_id = cast(:vendorId as uuid))"
            + " and (cast(:status as text) is null or case when x.status in ('SENT', 'PARTLY_RECEIVED')"
            + "   and x.delivery_expected < :today then 'OVERDUE' else x.status end = cast(:status as text)"
            + "   or x.status = cast(:status as text))"
            + " order by x.created_at, x.id, pl.sort";

    private static final String PURCHASE_BODY = " from pharmacy.goods_receipt x"
            + " join pharmacy.goods_receipt_line gl on gl.tenant_id = x.tenant_id and gl.goods_receipt_id = x.id and not gl.removed"
            + " join pharmacy.vendor v on v.tenant_id = x.tenant_id and v.id = x.vendor_id" + productJoin("gl")
            + " where x.tenant_id = :tenantId and x.status = 'POSTED' and x.store_id in (:storeIds) and " + range("x.posted_at")
            + PRODUCT_FILTER.formatted("gl")
            + " and (cast(:vendorId as uuid) is null or x.vendor_id = cast(:vendorId as uuid))";

    private static final String PURCHASE_REGISTER = "select x.store_id, gl.product_id, x.posted_at, x.doc_no, v.name, v.gstin,"
            + " x.invoice_no, x.invoice_date, " + PLABEL + ", coalesce(g.hsn, p.hsn), gl.batch_no, gl.qty::bigint, gl.taxable_paise,"
            + " gl.gst_rate, gl.gst_paise,"
            + " greatest(round(gl.billed_qty * gl.ptr * 100) - gl.taxable_paise, 0)::bigint, (gl.taxable_paise + gl.gst_paise)"
            + PURCHASE_BODY + " order by x.posted_at, x.id, gl.sort";

    private static final String PURCHASE_TOTALS_VENDOR = "select v.name as label, null::numeric as rate, sum(gl.qty)::bigint,"
            + " sum(gl.taxable_paise)::bigint, sum(gl.gst_paise)::bigint,"
            + " sum(greatest(round(gl.billed_qty * gl.ptr * 100) - gl.taxable_paise, 0))::bigint,"
            + " sum(gl.taxable_paise + gl.gst_paise)::bigint" + PURCHASE_BODY + " group by v.id, v.name order by lower(v.name)";
    private static final String PURCHASE_TOTALS_GST = "select null as label, gl.gst_rate as rate, sum(gl.qty)::bigint,"
            + " sum(gl.taxable_paise)::bigint, sum(gl.gst_paise)::bigint,"
            + " sum(greatest(round(gl.billed_qty * gl.ptr * 100) - gl.taxable_paise, 0))::bigint,"
            + " sum(gl.taxable_paise + gl.gst_paise)::bigint" + PURCHASE_BODY + " group by gl.gst_rate order by gl.gst_rate";

    private static final String DEBIT_NOTES_PENDING = "select x.store_id, null::uuid, x.doc_no, v.name, x.credit_total_paise, x.status,"
            + " (cast(:today as date) - (x.created_at at time zone 'Asia/Kolkata')::date)::bigint,"
            + " case when cast(:today as date) - (x.created_at at time zone 'Asia/Kolkata')::date > 30 then 'Yes' else 'No' end"
            + " from pharmacy.debit_note x join pharmacy.vendor v on v.tenant_id = x.tenant_id and v.id = x.vendor_id"
            + " where x.tenant_id = :tenantId and x.store_id in (:storeIds) and x.status in ('CREATED', 'PICKED', 'HANDED_OVER')"
            + " and (cast(:vendorId as uuid) is null or x.vendor_id = cast(:vendorId as uuid))"
            + " order by x.created_at, x.id";

    // ---- SAH-80-21, Sales & compliance (nothing here is a free-text note; the H1 register carries the patient name the law needs) ----

    /** The reason key as stored; ReportService turns it into the label of its reason list (pass-2 m7), never from the key text. */
    private static final String REASON_TEXT = "%s";

    /**
     * Bills of the day per store, less the returns completed that day (for the product asked, when one is); cost on the batch
     * purchase rate (landed, OQ-79-6) less the cost of the returned units that went back to AVAILABLE stock (a unit sent to
     * quarantine is not back on the shelf, so its cost stays). Quantity, discount and tax are those of the bills.
     */
    private static final String SALES_MARGIN = "select a.store_id, null::uuid, a.day, a.store_name, a.bills, a.qty,"
            + " (a.net - coalesce(rr.refunded, 0))::bigint, a.discount, a.tax, (a.cost - coalesce(rr.returned_cost, 0))::bigint,"
            + " case when a.net - coalesce(rr.refunded, 0) > 0"
            + " then round((a.net - coalesce(rr.refunded, 0) - (a.cost - coalesce(rr.returned_cost, 0))) * 100.0"
            + " / (a.net - coalesce(rr.refunded, 0)), 1) end"
            + " from (select b.store_id, (b.saved_at at time zone 'Asia/Kolkata')::date as day, s.name as store_name,"
            + " count(distinct b.id)::bigint as bills, coalesce(sum(l.qty), 0)::bigint as qty,"
            + " coalesce(sum(l.net_paise), 0)::bigint as net, coalesce(sum(l.discount_paise), 0)::bigint as discount,"
            + " coalesce(sum(l.tax_paise), 0)::bigint as tax,"
            + " coalesce(round(sum(l.qty * ba.purchase_rate) * 100), 0)::bigint as cost"
            + " from pharmacy.bill b join pharmacy.bill_line l on l.tenant_id = b.tenant_id and l.bill_id = b.id"
            + " join pharmacy.store s on s.tenant_id = b.tenant_id and s.id = b.store_id"
            + " join pharmacy.batch ba on ba.tenant_id = l.tenant_id and ba.id = l.batch_id"
            + " where b.tenant_id = :tenantId and b.store_id in (:storeIds) and b.status = 'SAVED' and " + range("b.saved_at")
            + PRODUCT_FILTER.formatted("l")
            + " group by b.store_id, s.name, (b.saved_at at time zone 'Asia/Kolkata')::date) a"
            + " left join (select r.store_id, (r.completed_at at time zone 'Asia/Kolkata')::date as day,"
            + " sum(rl.refund_paise)::bigint as refunded,"
            + " coalesce(round(sum(case when rl.destination = 'AVAILABLE' then rl.qty * rb.purchase_rate else 0 end) * 100), 0)::bigint"
            + " as returned_cost from pharmacy.sales_return r"
            + " join pharmacy.sales_return_line rl on rl.tenant_id = r.tenant_id and rl.sales_return_id = r.id"
            + " join pharmacy.batch rb on rb.tenant_id = rl.tenant_id and rb.id = rl.batch_id"
            + " where r.tenant_id = :tenantId and r.store_id in (:storeIds) and r.status = 'COMPLETED'"
            + " and rl.destination <> 'REFUSED' and " + range("r.completed_at") + PRODUCT_FILTER.formatted("rl")
            + " group by r.store_id, (r.completed_at at time zone 'Asia/Kolkata')::date) rr"
            + " on rr.store_id = a.store_id and rr.day = a.day"
            + " order by a.day, a.store_name";

    private static final String SALES_RETURNS = "select r.store_id, rl.product_id, r.credit_note_no, bl.bill_no, r.completed_at,"
            + " " + PLABEL + ", ba.batch_no, rl.qty::bigint, " + REASON_TEXT.formatted("r.reason_key") + ", "
            + REASON_TEXT.formatted("rl.condition_key") + ","
            + " (select string_agg(f.mode, ', ' order by f.mode) from pharmacy.refund f where f.tenant_id = r.tenant_id"
            + "   and f.sales_return_id = r.id), rl.refund_paise,"
            + " (select d.decided_by from pharmacy.approval_decision d where d.tenant_id = r.tenant_id"
            + "   and d.request_id = r.approval_request_id and d.decision = 'APPROVED' order by d.level_no desc limit 1),"
            + " r.completed_by"
            + " from pharmacy.sales_return r"
            + " join pharmacy.sales_return_line rl on rl.tenant_id = r.tenant_id and rl.sales_return_id = r.id"
            + " join pharmacy.bill bl on bl.tenant_id = r.tenant_id and bl.id = r.bill_id"
            + " join pharmacy.batch ba on ba.tenant_id = rl.tenant_id and ba.id = rl.batch_id" + productJoin("rl")
            + " where r.tenant_id = :tenantId and r.store_id in (:storeIds) and r.status = 'COMPLETED'"
            + " and rl.destination <> 'REFUSED' and " + range("r.completed_at") + PRODUCT_FILTER.formatted("rl")
            + " order by r.completed_at, r.id, rl.id";

    /** Bill lines as sold (a cancelled bill is marked) and credit-note lines as negative rows; no buyer name. */
    private static final String GST_SALES_REGISTER = "select u.store_id, u.product_id, u.happened, u.no, u.hsn, u.gst_rate,"
            + " u.taxable, u.cgst, u.sgst, u.total, u.cancelled from ("
            + " select bl.store_id, l.product_id, bl.saved_at as happened, bl.bill_no as no, l.hsn, l.gst_rate,"
            + " l.taxable_paise::bigint as taxable, ((l.tax_paise + 1) / 2)::bigint as cgst,"
            + " (l.tax_paise - (l.tax_paise + 1) / 2)::bigint as sgst, l.net_paise::bigint as total,"
            + " case when bl.status = 'CANCELLED' then 'Yes' else 'No' end as cancelled"
            + " from pharmacy.bill_line l join pharmacy.bill bl on bl.tenant_id = l.tenant_id and bl.id = l.bill_id"
            + " where bl.tenant_id = :tenantId and bl.store_id in (:storeIds) and " + range("bl.saved_at")
            + PRODUCT_FILTER.formatted("l")
            + " union all"
            + " select r.store_id, rl.product_id, r.completed_at, r.credit_note_no, l.hsn, l.gst_rate,"
            + " (-rl.taxable_paise)::bigint, (-((rl.tax_paise + 1) / 2))::bigint, (-(rl.tax_paise - (rl.tax_paise + 1) / 2))::bigint,"
            + " (-rl.refund_paise)::bigint, 'No'"
            + " from pharmacy.sales_return r"
            + " join pharmacy.sales_return_line rl on rl.tenant_id = r.tenant_id and rl.sales_return_id = r.id"
            + " join pharmacy.bill_line l on l.tenant_id = rl.tenant_id and l.id = rl.bill_line_id"
            + " where r.tenant_id = :tenantId and r.store_id in (:storeIds) and r.status = 'COMPLETED'"
            + " and rl.destination <> 'REFUSED' and " + range("r.completed_at") + PRODUCT_FILTER.formatted("rl")
            + ") u order by u.happened, u.no";

    private static final String H1_REGISTER = "select h.store_id, h.product_id, h.occurred_at,"
            + " case when h.reversal_reason = 'RETURNED' and h.reversal_ref is not null then h.reversal_ref else h.bill_no end,"
            + " h.kind, h.patient_name, h.patient_age::bigint, h.patient_address, h.prescriber_name, h.prescriber_reg_no,"
            + " h.prescription_date, " + PLABEL + ", ba.batch_no, h.qty::bigint, h.pharmacist_membership_id,"
            + " " + REASON_TEXT.formatted("h.override_reason_key") + ", h.patient_id"
            + " from pharmacy.h1_register_row h"
            + " join pharmacy.batch ba on ba.tenant_id = h.tenant_id and ba.id = h.batch_id" + productJoin("h")
            + " where h.tenant_id = :tenantId and h.store_id in (:storeIds) and " + range("h.occurred_at")
            + PRODUCT_FILTER.formatted("h") + " order by h.occurred_at, h.id";

    /** The AVAILABLE ledger up to the end of the range with the latest closed audit count; no patient. */
    private static final String SCHEDULE_X_REGISTER = "select a.store_id, a.product_id, a.store_name, a.pname,"
            + " case when a.ndps then 'Yes' else 'No' end, a.opening, a.received, a.issued, a.closing, c.counted,"
            + " case when c.counted is null then 'Not counted' when c.counted = a.closing then 'Match' else 'Review' end"
            + " from (select m.store_id, m.product_id, s.name as store_name, " + PLABEL + " as pname,"
            + " coalesce(g.ndps, p.ndps, false) as ndps,"
            + " coalesce(sum(case when m.occurred_at < (cast(:from as date)::timestamp at time zone 'Asia/Kolkata')"
            + "   then m.qty_in - m.qty_out end), 0)::bigint as opening,"
            + " coalesce(sum(case when m.occurred_at >= (cast(:from as date)::timestamp at time zone 'Asia/Kolkata')"
            + "   then m.qty_in end), 0)::bigint as received,"
            + " coalesce(sum(case when m.occurred_at >= (cast(:from as date)::timestamp at time zone 'Asia/Kolkata')"
            + "   then m.qty_out end), 0)::bigint as issued,"
            + " coalesce(sum(m.qty_in - m.qty_out), 0)::bigint as closing"
            + " from pharmacy.stock_ledger m"
            + " join pharmacy.store s on s.tenant_id = m.tenant_id and s.id = m.store_id" + productJoin("m")
            + " where m.tenant_id = :tenantId and m.store_id in (:storeIds) and m.state = 'AVAILABLE'"
            + " and m.occurred_at < ((cast(:to as date) + 1)::timestamp at time zone 'Asia/Kolkata')"
            + " and (coalesce(p.schedule, g.schedule) = 'X' or coalesce(g.ndps, p.ndps, false))"
            + PRODUCT_FILTER.formatted("m")
            + " group by m.store_id, m.product_id, s.name, " + PLABEL + ", coalesce(g.ndps, p.ndps, false)) a"
            + " left join lateral (select (select sum(l3.counted_qty) from pharmacy.stock_audit_line l3"
            + "   where l3.tenant_id = :tenantId and l3.stock_audit_id = la.id and l3.product_id = a.product_id"
            + "   and not l3.not_counted and l3.counted_qty is not null)::bigint as counted"
            + "   from (select au.id from pharmacy.stock_audit au join pharmacy.stock_audit_line l2"
            + "   on l2.tenant_id = au.tenant_id and l2.stock_audit_id = au.id"
            + "   where au.tenant_id = :tenantId and au.store_id = a.store_id and au.status = 'CLOSED'"
            + "   and l2.product_id = a.product_id and not l2.not_counted and l2.counted_qty is not null"
            + "   order by au.closed_at desc limit 1) la) c on true"
            + " where a.opening <> 0 or a.received > 0 or a.issued > 0 or a.closing <> 0"
            + " order by a.store_name, a.pname";

    private static final String ACTIVITY_LOG = "select x.store_id, null::uuid, x.happened, x.store_name, x.type, x.doc_no,"
            + " x.person, " + REASON_TEXT.formatted("x.reason_key") + " from ("
            + " select e.store_id, e.occurred_at as happened, s.name as store_name, e.actor_membership_id as person,"
            + " e.reason_key, coalesce(b.bill_no, rb.bill_no, sa.doc_no) as doc_no,"
            + " case when e.event = 'SECOND_SIGNATURE' then 'Second signature'"
            + " when e.doc_type = 'BILL' and e.event = 'ALLERGY_OVERRIDE' then 'Allergy override'"
            + " when e.doc_type = 'BILL' and e.event = 'H1_OVERRIDE' then 'H1 override'"
            + " when e.doc_type = 'SALE_DISCOUNT' and e.event = 'DISCOUNT_REQUESTED' then 'Discount requested'"
            + " when e.doc_type = 'SALE_DISCOUNT' and e.event = 'DISCOUNT_DECIDED' then 'Discount decided'"
            + " when e.doc_type = 'STOCK_ADJUSTMENT' and e.event = 'POSTED' then 'Stock adjusted'"
            + " when e.doc_type = 'BILL' and e.event = 'CANCELLED' then 'Bill cancelled'"
            + " when e.doc_type = 'SALES_RETURN' then 'Sales return' end as type"
            + " from pharmacy.document_event e"
            + " join pharmacy.store s on s.tenant_id = e.tenant_id and s.id = e.store_id"
            + " left join pharmacy.bill b on e.doc_type = 'BILL' and b.tenant_id = e.tenant_id and b.id = e.doc_id"
            + " left join pharmacy.sales_return sr on e.doc_type = 'SALES_RETURN' and sr.tenant_id = e.tenant_id"
            + "   and sr.id = e.doc_id"
            + " left join pharmacy.bill rb on rb.tenant_id = sr.tenant_id and rb.id = sr.bill_id"
            + " left join pharmacy.stock_adjustment sa on e.doc_type = 'STOCK_ADJUSTMENT' and sa.tenant_id = e.tenant_id"
            + "   and sa.id = e.doc_id"
            + " where e.tenant_id = :tenantId and e.store_id in (:storeIds) and " + range("e.occurred_at") + ") x"
            + " where x.type is not null order by x.happened";

    private static final String LEDGER_BODY = " from pharmacy.stock_ledger l"
            + " join pharmacy.store s on s.tenant_id = l.tenant_id and s.id = l.store_id"
            + " join pharmacy.batch b on b.tenant_id = l.tenant_id and b.id = l.batch_id"
            + " where l.tenant_id = :tenantId and l.product_id = cast(:productId as uuid) and l.store_id in (:storeIds)"
            + " and " + range("l.occurred_at");
    private static final String LEDGER = "select l.id, l.occurred_at, l.txn_type, l.doc_no, l.store_id, s.name as store_name,"
            + " s.type as store_type, l.state, l.batch_id, b.batch_no, b.expiry_date, l.qty_in, l.qty_out, l.balance_after,"
            + " l.actor_membership_id, l.second_signer_membership_id, l.reverses_ledger_id" + LEDGER_BODY
            + " order by l.occurred_at, l.id limit :limit offset :offset";

    private static final Map<ReportDef, String> QUERIES = new EnumMap<>(ReportDef.class);

    static {
        QUERIES.put(ReportDef.STORE_STOCK, STORE_STOCK);
        QUERIES.put(ReportDef.BATCH_EXPIRY, BATCH_EXPIRY);
        QUERIES.put(ReportDef.BELOW_REORDER, BELOW_REORDER);
        QUERIES.put(ReportDef.INDENT_REGISTER, INDENT_REGISTER);
        QUERIES.put(ReportDef.TRANSFER_REGISTER, TRANSFER_REGISTER);
        QUERIES.put(ReportDef.WARD_CONSUMPTION, WARD_CONSUMPTION);
        QUERIES.put(ReportDef.RETURNS_WRITE_OFFS, RETURNS_WRITE_OFFS);
        QUERIES.put(ReportDef.PO_VS_GRN, PO_VS_GRN);
        QUERIES.put(ReportDef.PURCHASE_REGISTER, PURCHASE_REGISTER);
        QUERIES.put(ReportDef.DEBIT_NOTES_PENDING, DEBIT_NOTES_PENDING);
        QUERIES.put(ReportDef.SALES_MARGIN, SALES_MARGIN);
        QUERIES.put(ReportDef.SALES_RETURNS, SALES_RETURNS);
        QUERIES.put(ReportDef.GST_SALES_REGISTER, GST_SALES_REGISTER);
        QUERIES.put(ReportDef.H1_REGISTER, H1_REGISTER);
        QUERIES.put(ReportDef.SCHEDULE_X_REGISTER, SCHEDULE_X_REGISTER);
        QUERIES.put(ReportDef.ACTIVITY_LOG, ACTIVITY_LOG);
    }

    private final JdbcClient jdbc;

    public JdbcReportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static String query(ReportDef report) {
        String sql = QUERIES.get(report);
        if (sql == null) {
            throw new IllegalArgumentException("No query for this report.");
        }
        return sql;
    }

    /** Binds only the names the statement uses, so no unused named parameter is sent. */
    private StatementSpec spec(String sql, Filter f) {
        StatementSpec spec = jdbc.sql(sql).param("tenantId", f.tenantId()).param("storeIds", f.storeIds());
        if (sql.contains(":from")) {
            spec = spec.param("from", f.from()).param("to", f.to());
        }
        if (sql.contains(":productId")) {
            spec = spec.param("productId", f.productId());
        }
        if (sql.contains(":today")) {
            spec = spec.param("today", f.today());
        }
        if (sql.contains(":vendorId")) {
            spec = spec.param("vendorId", f.vendorId());
        }
        if (sql.contains(":status")) {
            spec = spec.param("status", f.status());
        }
        return spec;
    }

    @Override
    public List<Row> rows(ReportDef report, Filter filter, int limit, int offset) {
        int width = report.columns().size();
        return spec(query(report) + " limit :limit offset :offset", filter).param("limit", limit).param("offset", offset)
                .query((rs, n) -> row(rs, width, report)).list();
    }

    @Override
    public long count(ReportDef report, Filter filter) {
        String inner = query(report);
        int order = inner.lastIndexOf(" order by ");
        String unordered = order > 0 ? inner.substring(0, order) : inner;
        return spec("select count(*) from (" + unordered + ") counted", filter).query(Long.class).single();
    }

    private static Row row(ResultSet rs, int width, ReportDef report) throws SQLException {
        List<Object> cells = new ArrayList<>(width);
        for (int i = 0; i < width; i++) {
            cells.add(cell(rs.getObject(i + 3), report.columns().get(i)));
        }
        UUID patientId = report == ReportDef.H1_REGISTER ? rs.getObject(width + 3, UUID.class) : null;
        return new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), cells, patientId);
    }

    private static Object cell(Object value, ReportDef.Col column) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp ts) {
            return ts.toInstant();
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        if (value instanceof UUID id) {
            return new PersonCell(id);
        }
        if (value instanceof BigDecimal number) {
            return column.kind() == ReportDef.Kind.PERCENT ? number : (Object) Long.valueOf(number.longValue());
        }
        if (value instanceof Number number) {
            return column.kind() == ReportDef.Kind.PERCENT ? (Object) BigDecimal.valueOf(number.doubleValue())
                    : (Object) Long.valueOf(number.longValue());
        }
        return value.toString();
    }

    @Override
    public List<Total> purchaseTotals(Filter filter) {
        List<Total> totals = new ArrayList<>();
        // column order of the register: date .. qty (empty), taxablePaise, gstRate, gstPaise, discountPaise, totalPaise
        spec(PURCHASE_TOTALS_VENDOR, filter).query((rs, n) -> totals(rs, rs.getString("label"), null)).list()
                .forEach(totals::add);
        spec(PURCHASE_TOTALS_GST, filter).query((rs, n) -> totals(rs, null, rs.getBigDecimal("rate"))).list()
                .forEach(totals::add);
        return totals;
    }

    private static Total totals(ResultSet rs, String label, BigDecimal rate) throws SQLException {
        List<Object> cells = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            cells.add(null);
        }
        cells.add(rs.getLong(3));
        cells.add(rs.getLong(4));
        cells.add(rate == null ? null : rate.stripTrailingZeros().toPlainString());
        cells.add(rs.getLong(5));
        cells.add(rs.getLong(6));
        cells.add(rs.getLong(7));
        return new Total(label != null ? "Vendor: " + label : "GST " + rate.stripTrailingZeros().toPlainString() + "%", cells);
    }

    @Override
    public List<LedgerLine> ledger(Filter filter, int limit, int offset) {
        return spec(LEDGER, filter).param("limit", limit).param("offset", offset).query((rs, n) -> new LedgerLine(
                rs.getObject("id", UUID.class), rs.getTimestamp("occurred_at").toInstant(), rs.getString("txn_type"),
                rs.getString("doc_no"), rs.getObject("store_id", UUID.class), rs.getString("store_name"),
                rs.getString("store_type"), StockState.valueOf(rs.getString("state")), rs.getObject("batch_id", UUID.class),
                rs.getString("batch_no"), rs.getDate("expiry_date").toLocalDate(), rs.getInt("qty_in"), rs.getInt("qty_out"),
                rs.getInt("balance_after"), rs.getObject("actor_membership_id", UUID.class),
                rs.getObject("second_signer_membership_id", UUID.class), rs.getObject("reverses_ledger_id", UUID.class))).list();
    }

    @Override
    public long ledgerCount(Filter filter) {
        return spec("select count(*)" + LEDGER_BODY, filter).query(Long.class).single();
    }

    /** Kept for the unit test of the wiring: every report has a query and no report has two. */
    static Collection<ReportDef> covered() {
        return QUERIES.keySet();
    }
}
