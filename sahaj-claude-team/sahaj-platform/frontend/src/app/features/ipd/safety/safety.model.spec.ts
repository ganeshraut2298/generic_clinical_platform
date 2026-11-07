import { VitalsSaved, savedToast } from '../nursing/nursing.model';
import { marOf, orderOf, slotOf, GIVEN_DOSE } from '../mar/mar.fixtures';
import { ACTIONS, ROUND_DUE, ROUND_LATE, ROW, SLOT_1030AM, SLOT_8AM } from './safety.fixtures';
import {
  ClinicalEvent,
  ClinicalEventPage,
  EscalationRow,
  SafetyBoard,
  ackToast,
  agoMinutesText,
  auditSubtitle,
  bannerNames,
  callToast,
  checkGive,
  dosesTileNumber,
  dosesTileText,
  giveName,
  roundKey,
  roundRowLine,
  roundStatusPill,
  roundSubtitle,
  closedToast,
  detailLine,
  eventLine,
  eventTime,
  kindIcon,
  mergeEvents,
  noticeBody,
  noticeHead,
  normalisePlan,
  openTileText,
  planCounterText,
  raisedLine,
  responseSubtitle,
  responseTitle,
  sameCounts,
  staleBoardText,
  statusPill,
  validateResponse,
} from './safety.model';

const event = (over: Partial<ClinicalEvent> = {}): ClinicalEvent => ({
  id: 'x1',
  kind: 'esc',
  text: 'Acknowledged escalation after 20 min',
  at: '2026-11-05T05:35:00Z',
  actorName: 'Dr. Meera Kulkarni',
  admissionId: 'a1',
  patientName: 'Asha Patil',
  bedCode: '101-A',
  ...over,
});

describe('safety.model', () => {
  describe('agoMinutesText', () => {
    it('writes minutes, then hours and minutes, from the server count', () => {
      expect(agoMinutesText(0)).toBe('0 min ago');
      expect(agoMinutesText(17)).toBe('17 min ago');
      expect(agoMinutesText(60)).toBe('1 h ago');
      expect(agoMinutesText(125)).toBe('2 h 5 min ago');
      expect(agoMinutesText(-3)).toBe('0 min ago');
      expect(agoMinutesText(Number.NaN)).toBe('0 min ago');
    });
  });

  describe('row texts', () => {
    it('raisedLine adds the target and the senior calls', () => {
      expect(raisedLine(ROW)).toBe('Raised by Priya Nair 17 min ago · target 15 min');
      expect(raisedLine({ ...ROW, targetMinutes: 60, seniorCalls: 2 })).toBe(
        'Raised by Priya Nair 17 min ago · target 1 hour · escalated to senior 2×',
      );
    });

    it('statusPill follows the status and the overdue flag', () => {
      expect(statusPill({ status: 'OPEN', pastTarget: false })).toEqual({ text: 'Not seen', tone: 'crit' });
      expect(statusPill({ status: 'OPEN', pastTarget: true })).toEqual({ text: 'Overdue · not seen', tone: 'crit' });
      expect(statusPill({ status: 'ACK', pastTarget: false })).toEqual({ text: 'Doctor reviewing', tone: 'warn' });
      expect(statusPill({ status: 'DONE', pastTarget: false })).toEqual({ text: 'Closed', tone: 'good' });
    });

    it('detailLine: none for Open, eye for Acknowledged, check with action, plan and doctor for Closed', () => {
      expect(detailLine(ROW)).toBeNull();
      expect(detailLine({ ...ROW, status: 'ACK', acknowledgedByName: 'Dr. Meera' })).toEqual({
        icon: 'eye',
        text: 'Acknowledged by Dr. Meera · response not recorded yet',
      });
      const done: EscalationRow = {
        ...ROW,
        status: 'DONE',
        acknowledgedByName: 'Dr. Meera',
        response: {
          action: 'BEDSIDE_REVIEW',
          actionLabel: 'Reviewed at the bedside',
          note: 'Oxygen up, repeat vitals in 30 minutes',
          closedByName: 'Dr. Meera',
          closedAt: '2026-11-05T05:50:00Z',
        },
      };
      expect(detailLine(done)).toEqual({
        icon: 'check',
        text: 'Reviewed at the bedside · Oxygen up, repeat vitals in 30 minutes · Dr. Meera',
      });
      expect(
        detailLine({ ...done, acknowledgedByName: null, response: { ...done.response!, note: null } })!.text,
      ).toBe('Reviewed at the bedside');
    });

    it('responseTitle and responseSubtitle', () => {
      expect(responseTitle(ROW)).toBe('Response · Asha Patil');
      expect(responseSubtitle(ROW)).toBe('EWS 8 (high) · raised 17 min ago by Priya Nair');
    });
  });

  describe('red notice', () => {
    const board = (view: SafetyBoard['view'], rows: EscalationRow[]): Pick<SafetyBoard, 'escalations' | 'view'> => ({
      view,
      escalations: rows,
    });

    it('writes singular and plural heads', () => {
      expect(noticeHead(1)).toBe('1 escalation past the response target.');
      expect(noticeHead(2)).toBe('2 escalations past the response target.');
    });

    it('lists only the escalations past target, from waitingMinutes, with the ending of the view', () => {
      const rows = [
        ROW,
        { ...ROW, id: 'e2', patientName: 'Ravi Shah', ewsScore: 6, waitingMinutes: 65 },
        { ...ROW, id: 'e3', patientName: 'Sunita More', pastTarget: false },
      ];
      expect(noticeBody(board('DOCTOR', rows))).toBe(
        'Asha Patil (EWS 8) raised 17 min ago; Ravi Shah (EWS 6) raised 1 h 5 min ago. Acknowledge, then record what you did.',
      );
      expect(noticeBody(board('NURSE', [ROW]))).toBe('Asha Patil (EWS 8) raised 17 min ago. Call the senior doctor now.');
    });
  });

  it('openTileText: past target in red, or all within target', () => {
    expect(openTileText({ pastTarget: 2 })).toEqual({ text: '2 past target', critical: true });
    expect(openTileText({ pastTarget: 0 })).toEqual({ text: 'All within target', critical: false });
  });

  it('bannerNames joins "name · EWS n" with commas', () => {
    expect(
      bannerNames([
        { escalationId: 'e1', admissionId: 'a1', patientName: 'Asha Patil', ewsScore: 8 },
        { escalationId: 'e2', admissionId: 'a2', patientName: 'Ravi Shah', ewsScore: 6 },
      ]),
    ).toBe('Asha Patil · EWS 8, Ravi Shah · EWS 6');
  });

  describe('toasts', () => {
    it('use the mockup wording, except Call senior (DEC-015)', () => {
      expect(ackToast('Asha Patil')).toBe('Acknowledged. Record what you did for Asha Patil after you see the patient.');
      expect(closedToast('Asha Patil')).toBe('Escalation for Asha Patil closed and recorded');
      expect(callToast('Asha Patil')).toBe('Call recorded for Asha Patil. No alert is sent: phone the senior doctor now.');
      expect(callToast('Asha Patil')).not.toContain('called for');
    });

    it('the Nursing toast is the same text when the vitals save re-opens an escalation', () => {
      const answer = {
        patientName: 'Asha Patil',
        saved: { ewsScore: 8, ewsRisk: 'HIGH' },
        escalation: {
          outcome: 'REOPENED',
          open: { id: 'e1', ewsScore: 8, ewsRisk: 'HIGH', targetMinutes: 15, status: 'OPEN', raisedAt: '2026-11-05T05:00:00Z' },
        },
      } as VitalsSaved;
      expect(savedToast(answer)).toBe(
        'Escalation raised for Asha Patil · EWS 8 · doctor review within 15 min. Tell the doctor now: no alert is sent.',
      );
    });
  });

  it('staleBoardText shows the time of the last good answer in IST', () => {
    expect(staleBoardText('2026-11-05T05:35:00Z')).toBe('Could not refresh. Showing data from 11:05 AM.');
    expect(staleBoardText(null)).toBe('Could not refresh.');
  });

  describe('validateResponse', () => {
    const ok = { action: 'BEDSIDE_REVIEW', note: 'Oxygen increased, repeat vitals in 30 minutes' };

    it('accepts a chosen action and a plan', () => {
      expect(validateResponse(ok, ACTIONS)).toEqual({});
    });

    it('refuses an empty plan after trimming, with the mockup message', () => {
      expect(validateResponse({ ...ok, note: '  \n ' }, ACTIONS).note).toBe('Write the plan and when vitals are next due.');
      expect(validateResponse({ ...ok, note: '' }, ACTIONS).note).toBe('Write the plan and when vitals are next due.');
    });

    it('refuses an action that is not on offer', () => {
      expect(validateResponse({ ...ok, action: '' }, ACTIONS).action).toBe('Choose what was done');
      expect(validateResponse({ ...ok, action: 'CLOSED_AT_DISCHARGE' }, ACTIONS).action).toBe('Choose what was done');
    });

    it('counts 1,000 characters after trimming, with line breaks as one, and refuses 1,001', () => {
      expect(validateResponse({ ...ok, note: ` ${'a'.repeat(1000)} ` }, ACTIONS)).toEqual({});
      expect(validateResponse({ ...ok, note: 'a'.repeat(1001) }, ACTIONS).note).toBe('Keep the plan to 1,000 characters.');
      expect(validateResponse({ ...ok, note: `${'a'.repeat(499)}\r\n${'b'.repeat(500)}` }, ACTIONS)).toEqual({});
    });

    it('allows line breaks and tabs but refuses control and text-direction characters', () => {
      expect(validateResponse({ ...ok, note: 'Line one\nLine two\tend' }, ACTIONS)).toEqual({});
      const refused = 'Plan cannot contain control or text-direction characters';
      expect(validateResponse({ ...ok, note: `bad${String.fromCharCode(7)}bell` }, ACTIONS).note).toBe(refused);
      expect(validateResponse({ ...ok, note: `bidi${String.fromCharCode(0x202e)}text` }, ACTIONS).note).toBe(refused);
      expect(validateResponse({ ...ok, note: `isolate${String.fromCharCode(0x2066)}text` }, ACTIONS).note).toBe(refused);
    });

    it('normalisePlan composes, turns CR LF into LF and strips', () => {
      expect(normalisePlan(' a\r\nb\rc\td ')).toBe('a\nb\nc d');
      expect(normalisePlan('e' + String.fromCharCode(0x301))).toBe(String.fromCharCode(0xe9));
    });
  });

  describe('audit trail', () => {
    it('uses the icon of the kind, history for an unknown one', () => {
      expect(kindIcon('give')).toBe('pill');
      expect(kindIcon('order')).toBe('emr');
      expect(kindIcon('verify')).toBe('shieldu');
      expect(kindIcon('esc')).toBe('siren');
      expect(kindIcon('view')).toBe('eye');
      expect(kindIcon('bg')).toBe('key');
      expect(kindIcon('deny')).toBe('alert');
      expect(kindIcon('stop')).toBe('x');
      expect(kindIcon('info')).toBe('history');
      expect(kindIcon('other')).toBe('history');
    });

    it('eventTime: the time today, the day and the time earlier (IST)', () => {
      const asOf = '2026-11-05T06:30:00Z';
      expect(eventTime('2026-11-05T05:35:00Z', asOf)).toBe('11:05 AM');
      expect(eventTime('2026-10-24T05:35:00Z', asOf)).toBe('24 Oct, 11:05 AM');
      // 20:00 UTC on 4 Nov is 1:30 AM IST on 5 Nov: today in India.
      expect(eventTime('2026-11-04T20:00:00Z', asOf)).toBe('1:30 AM');
      expect(eventTime('bad', asOf)).toBe('');
    });

    it('eventLine: the user, then the patient and bed only when the entry belongs to an admission', () => {
      expect(eventLine(event())).toBe('Dr. Meera Kulkarni · Asha Patil · bed 101-A');
      expect(eventLine(event({ admissionId: null, patientName: null, bedCode: null }))).toBe('Dr. Meera Kulkarni');
    });

    it('auditSubtitle depends on the view', () => {
      expect(auditSubtitle('DOCTOR')).toBe(
        'Every order, check, dose, escalation and record opened. Entries cannot be edited or deleted.',
      );
      expect(auditSubtitle('NURSE')).toBe('Your actions and your patients. Entries cannot be edited or deleted.');
    });

    describe('mergeEvents', () => {
      const e = (n: number) => event({ id: `x${n}` });
      const page = (ids: number[], nextCursor: string | null): ClinicalEventPage => ({
        asOf: '2026-11-05T06:30:00Z',
        items: ids.map(e),
        nextCursor,
      });

      it('takes the fresh page when only the first page is showing', () => {
        const merged = mergeEvents({ items: [e(3), e(2)], nextCursor: 'c1' }, page([4, 3], 'c2'));
        expect(merged.items.map((i) => i.id)).toEqual(['x4', 'x3']);
        expect(merged.nextCursor).toBe('c2');
      });

      it('puts new entries on top of older ones already loaded and keeps the older cursor', () => {
        const merged = mergeEvents({ items: [e(3), e(2), e(1)], nextCursor: 'old' }, page([4, 3], 'c2'));
        expect(merged.items.map((i) => i.id)).toEqual(['x4', 'x3', 'x2', 'x1']);
        expect(merged.nextCursor).toBe('old');
      });

      it('starts over when the pages share no entry, so no gap is shown', () => {
        const merged = mergeEvents({ items: [e(3), e(2), e(1)], nextCursor: 'old' }, page([9, 8], 'c9'));
        expect(merged.items.map((i) => i.id)).toEqual(['x9', 'x8']);
        expect(merged.nextCursor).toBe('c9');
      });
    });
  });

  it('sameCounts compares open, pastTarget, the dose numbers and the change token', () => {
    const A = 'AAAAAAAAAAAAAAAAAAAAAA';
    const c = (open: number, pastTarget: number, changeToken = A, dosesDue = 0, dosesLate = 0) => ({
      open,
      pastTarget,
      dosesDue,
      dosesLate,
      changeToken,
    });
    expect(sameCounts(c(2, 1, A, 1, 0), c(2, 1, A, 2, 0))).toBe(false);
    expect(sameCounts(c(2, 1, A, 1, 0), c(2, 1, A, 1, 1))).toBe(false);
    expect(sameCounts(c(2, 1, A, 1, 1), c(2, 1, A, 1, 1))).toBe(true);
    expect(sameCounts(c(2, 1), c(2, 1))).toBe(true);
    expect(sameCounts(c(2, 1), c(2, 0))).toBe(false);
    expect(sameCounts(c(2, 1), c(3, 1))).toBe(false);
    // One overdue escalation closes while another becomes overdue: the numbers are equal, the token is not.
    expect(sameCounts(c(2, 1), c(2, 1, 'BBBBBBBBBBBBBBBBBBBBBB'))).toBe(false);
  });

  it('planCounterText shows the code points against the limit with grouped digits', () => {
    expect(planCounterText(0)).toBe('0 / 1,000');
    expect(planCounterText(1012)).toBe('1,012 / 1,000');
  });

  it('validateResponse counts the plan in code points: 1,000 emoji pass, 1,001 do not', () => {
    const actions = [{ key: 'BEDSIDE_REVIEW' as const, label: 'Reviewed at the bedside' }];
    expect(validateResponse({ action: 'BEDSIDE_REVIEW', note: '😀'.repeat(1000) }, actions)).toEqual({});
    expect(validateResponse({ action: 'BEDSIDE_REVIEW', note: '😀'.repeat(1001) }, actions)).toEqual({
      note: 'Keep the plan to 1,000 characters.',
    });
  });

  describe('Medicine round (SAH-35)', () => {
    it('tile figure is Due plus Late, the small text is the Late count and says 0 overdue when none', () => {
      expect(dosesTileNumber({ dosesDue: 3, dosesLate: 2 })).toBe(5);
      expect(dosesTileNumber({ dosesDue: 0, dosesLate: 0 })).toBe(0);
      expect(dosesTileText({ dosesLate: 2 })).toBe('2 overdue');
      expect(dosesTileText({ dosesLate: 0 })).toBe('0 overdue');
    });

    it('the subtitle shows the server clock in IST', () => {
      expect(roundSubtitle('2026-11-05T05:35:00Z')).toBe(
        'Doses due in the next hour and doses not given on time. Clock: 11:05 AM.',
      );
    });

    it('writes the small line with the slot time as 8 AM, 2 PM, 8:30 AM', () => {
      expect(roundRowLine(ROUND_LATE)).toBe('Asha Patil · bed 101-A · 8 AM dose');
      expect(roundRowLine({ ...ROUND_LATE, slotAt: '2026-11-05T09:00:00Z' })).toBe('Asha Patil · bed 101-A · 2:30 PM dose');
      expect(roundRowLine({ ...ROUND_LATE, slotAt: '2026-11-05T08:30:00Z' })).toBe('Asha Patil · bed 101-A · 2 PM dose');
    });

    it('status pill: "N h late" red, at least 1; "Due now" amber', () => {
      expect(roundStatusPill({ state: 'LATE', lateHours: 3 })).toEqual({ text: '3 h late', tone: 'crit' });
      expect(roundStatusPill({ state: 'LATE', lateHours: 1 })).toEqual({ text: '1 h late', tone: 'crit' });
      expect(roundStatusPill({ state: 'LATE', lateHours: null })).toEqual({ text: '1 h late', tone: 'crit' });
      expect(roundStatusPill({ state: 'DUE', lateHours: null })).toEqual({ text: 'Due now', tone: 'warn' });
    });

    it('names the Give button after the medicine and the patient', () => {
      expect(giveName(ROUND_DUE)).toBe('Give Tab. Paracetamol 650 mg to Ravi Shah');
    });

    it('keys a dose by order and slot', () => {
      expect(roundKey(ROUND_LATE)).toBe(`o2|${SLOT_8AM}`);
      expect(roundKey(ROUND_LATE)).not.toBe(roundKey({ ...ROUND_LATE, slotAt: SLOT_1030AM }));
    });

    describe('checkGive (the re-check after the MAR read)', () => {
      const dueOrder = (state: 'DUE' | 'LATE' = 'LATE') =>
        orderOf({ orderId: 'o2', slots: [slotOf({ slotAt: SLOT_8AM, state, lateHours: 3 })] });

      it('opens with the order and the slot when the slot is Late or Due and can be given', () => {
        for (const state of ['LATE', 'DUE'] as const) {
          const mar = marOf({ orders: [dueOrder(state)] });
          const check = checkGive(mar, ROUND_LATE);
          expect(check.open).toBe(true);
          if (check.open) {
            expect(check.order.orderId).toBe('o2');
            expect(check.slot.state).toBe(state);
          }
        }
      });

      it('matches the slot by instant, not by text', () => {
        const mar = marOf({ orders: [orderOf({ orderId: 'o2', slots: [slotOf({ slotAt: '2026-11-05T08:00:00+05:30' })] })] });
        expect(checkGive(mar, ROUND_LATE).open).toBe(true);
      });

      it('refuses with the existing texts, in this order', () => {
        const slotWith = (state: 'GIVEN' | 'HELD' | 'REFUSED') =>
          marOf({
            orders: [
              orderOf({
                orderId: 'o2',
                slots: [slotOf({ slotAt: SLOT_8AM, state, canGive: false, dose: state === 'GIVEN' ? GIVEN_DOSE : null })],
              }),
            ],
          });
        expect(checkGive(marOf({ status: 'DISCHARGED', orders: [] }), ROUND_LATE)).toEqual({
          open: false,
          message: 'This patient is not admitted any more. Doses cannot be recorded.',
        });
        expect(checkGive(marOf({ orders: [] }), ROUND_LATE)).toEqual({
          open: false,
          message: 'This medicine was stopped. Its doses can no longer be recorded.',
        });
        for (const state of ['GIVEN', 'HELD', 'REFUSED'] as const) {
          expect(checkGive(slotWith(state), ROUND_LATE)).toEqual({ open: false, message: 'This dose is already recorded.' });
        }
        expect(checkGive(marOf({ canGive: false, giveLockReason: 'IPD_READONLY', orders: [dueOrder()] }), ROUND_LATE)).toEqual({
          open: false,
          message: 'IPD is read-only. Doses cannot be recorded.',
        });
        expect(checkGive(marOf({ canGive: false, giveLockReason: 'ROLE', orders: [dueOrder()] }), ROUND_LATE)).toEqual({
          open: false,
          message: 'Reload the page and try again.',
        });
        expect(checkGive(marOf({ orders: [orderOf({ orderId: 'o2', slots: [] })] }), ROUND_LATE)).toEqual({
          open: false,
          message: 'Reload the page and try again.',
        });
      });
    });
  });
});
