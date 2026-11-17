import { DETAILS } from '../emr/emr.fixtures';
import {
  NoticeCount,
  OrderNotice,
  OrderNoticeList,
  OrderNoticePage,
  OrderNoticePageRow,
  OrderNoticeSummary,
} from './order.model';

/** Test data in the shape of docs/api/ipd.yaml (SAH-76-3). Fictional names only. */
export const AS_OF = '2026-11-06T05:47:00Z';
export const TOKEN_A = 'AAAAAAAAAAAAAAAAAAAAAA';
export const TOKEN_B = 'BBBBBBBBBBBBBBBBBBBBBB';

export const count = (n = 0, stat = 0, overdue = 0): NoticeCount => ({ count: n, stat, overdue });

export const noticeOf = (over: Partial<OrderNotice> = {}): OrderNotice => ({
  id: 'n1',
  admissionId: 'a1',
  orderId: 'o1',
  orderVersion: 1,
  kind: 'NEW',
  priority: 'ROUTINE',
  drugKey: 'pcm',
  drugName: 'Tab. Paracetamol 650 mg',
  details: DETAILS,
  changes: [],
  reason: null,
  createdAt: '2026-11-06T05:35:00Z',
  createdByName: 'Dr. Meera Kulkarni',
  acknowledgedAt: null,
  acknowledgedByName: null,
  waitingMinutes: 12,
  overdue: false,
  stat: null,
  canAcknowledge: true,
  ...over,
});

export function listOf(items: OrderNotice[] = [noticeOf()], over: Partial<OrderNoticeList> = {}): OrderNoticeList {
  const open = items.filter((n) => n.acknowledgedAt === null);
  return {
    admissionId: 'a1',
    asOf: AS_OF,
    state: 'on',
    status: 'ADMITTED',
    open: count(open.length, open.filter((n) => n.priority === 'STAT').length, open.filter((n) => n.overdue).length),
    items,
    canAcknowledge: true,
    ...over,
  };
}

export const pageRowOf = (
  over: Partial<OrderNotice> = {},
  patientName = 'Asha Patil',
  bedCode = '101-A',
): OrderNoticePageRow => ({
  notice: noticeOf(over),
  patientName,
  bedCode,
});

export function pageOf(
  rows: OrderNoticePageRow[] = [pageRowOf()],
  over: Partial<OrderNoticePage> = {},
): OrderNoticePage {
  return { asOf: AS_OF, open: count(rows.length), items: rows, nextCursor: null, ...over };
}

export function summaryOf(over: Partial<OrderNoticeSummary> = {}): OrderNoticeSummary {
  return {
    asOf: AS_OF,
    total: count(0),
    byAdmission: [],
    changeToken: TOKEN_A,
    ...over,
  };
}
