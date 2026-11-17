import { ApiError, ProblemDetail } from '../../../core/http/problem-detail';
import { AT_8AM, MAR_AS_OF, ORDER_PRN, PRN_LINE, doseOf, marOf, orderOf, slotOf } from './mar.fixtures';
import {
  MISMATCH_LINE,
  OVERRIDE_AFTER_MISMATCH,
  changedMarker,
  checkLines,
  classifyGiveError,
  clockWithMinutes,
  doseDetailLine,
  doseText,
  foldToggleLabel,
  givenInput,
  historyLine,
  intervalText,
  lateText,
  marOrderLine,
  notGivenInput,
  nothingDue,
  orderLockLine,
  prnView,
  slotChip,
  visibleOrders,
} from './mar.model';

const apiError = (status: number, slug: string, detail: string, extra: Partial<ProblemDetail> = {}) =>
  new ApiError({ type: `https://sahaj.in/problems/${slug}`, title: slug, status, detail, ...extra });

describe('MAR model, SAH-76 nursing integration', () => {
  describe('slotChip: new states', () => {
    it('ON_HOLD, STOPPED and REPLACED are grey with the doctor and the time', () => {
      expect(slotChip(slotOf({ state: 'ON_HOLD', canGive: false, stateBy: 'Dr. Meera Kulkarni' })).small).toBe(
        'On hold by Dr. Meera Kulkarni',
      );
      expect(
        slotChip(slotOf({ state: 'STOPPED', canGive: false, stateBy: 'Dr. Meera Kulkarni', stateAt: '2026-11-06T08:30:00Z' })),
      ).toMatchObject({ kind: 'static', tone: 'grey', small: 'Stopped by Dr. Meera Kulkarni at 2 PM' });
      expect(
        slotChip(slotOf({ state: 'REPLACED', canGive: false, stateBy: 'Dr. Meera Kulkarni', stateAt: MAR_AS_OF })).small,
      ).toBe('Replaced by a change by Dr. Meera Kulkarni at 11:05 AM');
    });

    it('WAITING reads Queried for a queried order', () => {
      expect(slotChip(slotOf({ state: 'WAITING' }), { status: 'QUERIED' }).small).toBe('Queried');
      expect(slotChip(slotOf({ state: 'WAITING' }), { status: 'PENDING' }).small).toBe('Waiting for pharmacist');
    });

    it('a given override dose shows "<time> · override"', () => {
      const dose = doseOf({ method: 'OVERRIDE', overrideReason: { key: 'NO_BARCODE', label: 'Medicine has no barcode' } });
      expect(slotChip(slotOf({ state: 'GIVEN', canGive: false, dose })).small).toBe('8:04 AM · override');
    });

    it('LATE: hours, or minutes when under an hour, or STAT overdue minutes', () => {
      expect(slotChip(slotOf({ state: 'LATE', lateHours: 3, lateMinutes: 190 })).small).toBe('3 h late · Give');
      expect(slotChip(slotOf({ state: 'LATE', lateHours: 1, lateMinutes: 40 })).small).toBe('40 min late · Give');
      expect(slotChip(slotOf({ state: 'LATE', lateHours: 3 })).small).toBe('3 h late · Give');
      const stat = { details: { kind: 'ONCE' as const } };
      expect(slotChip(slotOf({ state: 'LATE', lateMinutes: 6 }), stat).small).toBe('STAT · overdue by 6 min · Give');
      expect(slotChip(slotOf({ state: 'DUE' }), stat).small).toBe('STAT · Give');
      expect(lateText({ lateHours: null, lateMinutes: null })).toBe('1 h late');
    });
  });

  describe('order block', () => {
    it('the line carries the dose when the order has one', () => {
      const line = marOrderLine({
        route: 'Oral',
        frequencyLabel: 'Twice daily',
        orderedByName: 'Dr. M',
        details: { doseAmount: 1, doseUnit: 'TABLET' },
      });
      expect(line).toBe('1 tablet · Oral · Twice daily · Dr. M');
      expect(doseText({ doseAmount: null, doseUnit: null })).toBeNull();
      expect(doseText({ doseAmount: 500, doseUnit: 'MG' })).toBe('500 mg');
    });

    it('the changed marker shows from version 2', () => {
      expect(changedMarker({ changed: true, orderVersion: 3 })).toBe('Changed · v3');
      expect(changedMarker({ changed: false, orderVersion: 1 })).toBeNull();
    });

    it('lock lines: pharmacist, query, hold; none for a STAT dose that may be given first', () => {
      expect(orderLockLine({ status: 'PENDING', hold: null, slots: [slotOf()] })).toBe(
        'Cannot be given until the pharmacist checks the order.',
      );
      expect(
        orderLockLine({ status: 'PENDING', hold: null, slots: [slotOf({ beforeVerification: true, canGive: true })] }),
      ).toBeNull();
      expect(orderLockLine({ status: 'QUERIED', hold: null, slots: [] })).toBe(
        'Cannot be given: the pharmacist has a query for the doctor.',
      );
      expect(
        orderLockLine({ status: 'ON_HOLD', hold: { heldAt: MAR_AS_OF, heldByName: 'Dr. M', reason: null }, slots: [] }),
      ).toBe('On hold by Dr. M');
      expect(orderLockLine({ status: 'ACTIVE', hold: null, slots: [] })).toBeNull();
    });

    it('fold: the server marks the blocks, the button toggles them', () => {
      const orders = [orderOf({ orderId: 'a' }), orderOf({ orderId: 'b', folded: true })];
      expect(visibleOrders(orders, false).map((o) => o.orderId)).toEqual(['a']);
      expect(visibleOrders(orders, true).map((o) => o.orderId)).toEqual(['a', 'b']);
      expect(foldToggleLabel(1, false)).toBe('Show 1 medicine with nothing due now');
      expect(foldToggleLabel(3, false)).toBe('Show 3 medicines with nothing due now');
      expect(foldToggleLabel(3, true)).toBe('Hide medicines with nothing due');
    });

    it('"Nothing due right now" shows when nothing is late or due and the fold is closed', () => {
      const none = marOf({ counters: { late: 0, dueNow: 0, given: 2 } });
      expect(nothingDue(none, false)).toBe(true);
      expect(nothingDue(none, true)).toBe(false);
      expect(nothingDue(marOf({ counters: { late: 1, dueNow: 0, given: 0 } }), false)).toBe(false);
      expect(nothingDue(marOf({ orders: [] }), false)).toBe(false);
    });
  });

  describe('PRN line', () => {
    it('shows the interval, the doses used and no lock when it can be given', () => {
      expect(prnView(PRN_LINE, ORDER_PRN)).toEqual({
        indication: 'Fever above 38 C',
        interval: 'Minimum interval 4 h',
        used: '2 of 4 in 24 h',
        lock: null,
        canGive: true,
      });
    });

    it('too soon and daily maximum show the server time in IST', () => {
      const soon = prnView({ ...PRN_LINE, lockReason: 'TOO_SOON', canGive: false, nextAllowedAt: '2026-11-06T09:00:00Z' }, ORDER_PRN);
      expect(soon.lock).toBe('Too soon. Next dose allowed at 2:30 PM.');
      expect(soon.canGive).toBe(false);
      const max = prnView({ ...PRN_LINE, lockReason: 'MAX_REACHED', canGive: false, nextAllowedAt: '2026-11-06T12:40:00Z' }, ORDER_PRN);
      expect(max.lock).toBe('Daily maximum reached. Next dose allowed at 6:10 PM.');
    });

    it('hold, stop and not-verified locks', () => {
      const held = { ...ORDER_PRN, hold: { heldAt: MAR_AS_OF, heldByName: 'Dr. M', reason: null } };
      expect(prnView({ ...PRN_LINE, lockReason: 'ON_HOLD', canGive: false }, held).lock).toBe('On hold by Dr. M');
      expect(prnView({ ...PRN_LINE, lockReason: 'NOT_VERIFIED', canGive: false }, ORDER_PRN).lock).toBe(
        'Cannot be given until the pharmacist checks the order.',
      );
      expect(prnView({ ...PRN_LINE, lockReason: 'STOPPED', canGive: false }, ORDER_PRN).lock).toContain('was stopped');
    });

    it('NOT_STARTED and ENDED lock the line with the contract texts (0.4.3)', () => {
      const notStarted = prnView({ ...PRN_LINE, lockReason: 'NOT_STARTED', canGive: false }, ORDER_PRN);
      expect(notStarted.lock).toBe('Not started yet');
      expect(notStarted.canGive).toBe(false);
      const ended = prnView({ ...PRN_LINE, lockReason: 'ENDED', canGive: false }, ORDER_PRN);
      expect(ended.lock).toBe('Ended');
      expect(ended.canGive).toBe(false);
    });

    it('interval and clock texts', () => {
      expect(intervalText(30)).toBe('30 min');
      expect(intervalText(90)).toBe('1 h 30 min');
      expect(intervalText(240)).toBe('4 h');
      expect(clockWithMinutes('2026-11-06T08:30:00Z')).toBe('2:00 PM');
    });

    it('a PRN dose has no slot in the request and uses the order version', () => {
      const body = givenInput('k', { orderVersion: 4 }, null, false, {
        wristband: 'PT-1',
        said: true,
        code: 'c',
        override: null,
        nurse: '',
        pin: '',
      });
      expect(body.orderVersion).toBe(4);
      expect(Object.keys(body)).not.toContain('slotAt');
    });
  });

  describe('request bodies', () => {
    const order = { orderVersion: 2 };
    const slot = { slotAt: AT_8AM, orderVersion: 1 };
    const entered = { wristband: 'PT-00042', said: true, code: '', nurse: '', pin: '' };

    it('the slot version wins over the order version', () => {
      expect(givenInput('k', order, slot, false, { ...entered, override: null }).orderVersion).toBe(1);
    });

    it('an override sends the reason and the note, and an empty reason as null', () => {
      expect(
        givenInput('k', order, slot, false, { ...entered, override: { reasonKey: 'NO_BARCODE', note: 'Read the strip' } }).override,
      ).toEqual({ reasonKey: 'NO_BARCODE', note: 'Read the strip' });
      expect(
        givenInput('k', order, slot, false, { ...entered, override: { reasonKey: '', note: '' } }).override?.reasonKey,
      ).toBeNull();
    });

    it('an override is not sent when a medicine code is entered (0.4.3: it covers the empty code only)', () => {
      const withCode = givenInput('k', order, slot, false, {
        ...entered,
        code: '8901234567890',
        override: { reasonKey: 'NO_BARCODE', note: 'Read the strip' },
      });
      expect(withCode.override).toBeNull();
      expect(withCode.medicineCode).toBe('8901234567890');
      const blank = givenInput('k', order, slot, false, {
        ...entered,
        code: '   ',
        override: { reasonKey: 'EMERGENCY', note: 'Read the strip' },
      });
      expect(blank.override).toEqual({ reasonKey: 'EMERGENCY', note: 'Read the strip' });
    });

    it('an override still sends the wristband text', () => {
      const body = givenInput('k', order, slot, false, {
        ...entered,
        override: { reasonKey: 'SCANNER_DOWN', note: 'Read the strip' },
      });
      expect(body.wristband).toBe('PT-00042');
    });

    it('Record not given sends the note only with the reason Other', () => {
      expect(notGivenInput('k', order, slot, 'OTHER', 'Patient asleep')).toMatchObject({ note: 'Patient asleep' });
      expect(Object.keys(notGivenInput('k', order, slot, 'VOMITED', 'x'))).not.toContain('note');
    });
  });

  describe('Checked by the system', () => {
    const mar = marOf();
    const order = { route: 'Oral', lastGivenAt: null };
    const noAllergy = { ...mar, patient: { ...mar.patient, allergyStatus: 'NONE_KNOWN' as const, allergyText: null } };

    it('lists the lines in the order of the story, patient and medicine left to the server', () => {
      const lines = checkLines(noAllergy, order, slotOf());
      expect(lines.map((l) => l.text)).toEqual([
        'Right patient',
        'Right medicine and dose',
        'Right route · Oral',
        'Right time',
        'No known allergy',
        'No earlier dose',
      ]);
      expect(lines.filter((l) => !l.known).map((l) => l.text)).toEqual(['Right patient', 'Right medicine and dose']);
    });

    it('a late slot reads "Late: 40 min after schedule"', () => {
      const lines = checkLines(noAllergy, order, slotOf({ state: 'LATE', lateMinutes: 40, lateHours: 1 }));
      expect(lines[3]).toEqual({ text: 'Late: 40 min after schedule', known: true, warn: true });
    });

    it('allergy lines: recorded, not recorded', () => {
      const withAllergy = { ...mar, patient: { ...mar.patient, allergyStatus: 'RECORDED' as const, allergyText: 'Penicillin' } };
      expect(checkLines(withAllergy, order, slotOf())[4].text).toBe('Allergy checked · no clash with Penicillin');
      const notRecorded = { ...mar, patient: { ...mar.patient, allergyStatus: 'NOT_RECORDED' as const, allergyText: null } };
      expect(checkLines(notRecorded, order, slotOf())[4]).toMatchObject({ text: 'Allergies not recorded', warn: true });
    });

    it('last given shows the time of the last dose', () => {
      const lines = checkLines(noAllergy, { route: 'SC', lastGivenAt: '2026-11-06T02:34:00Z' }, slotOf());
      expect(lines[5].text).toBe('Last given 8:04 AM');
    });
  });

  describe('classifyGiveError: new answers', () => {
    it('the wrong patient line has no name', () => {
      expect(MISMATCH_LINE).toBe(
        'Wrong patient. This band belongs to another patient. Check the patient before giving anything.',
      );
    });

    it('409 medicine-mismatch: the STOP with the server text', () => {
      const f = classifyGiveError(apiError(409, 'medicine-mismatch', 'Wrong medicine. This is Inj. Heparin, not Tab. Aspirin.'));
      expect(f).toEqual({ kind: 'code-mismatch', message: 'Wrong medicine. This is Inj. Heparin, not Tab. Aspirin.' });
    });

    it('409 override-after-mismatch: the STOP line under the code box with the contract detail', () => {
      const detail =
        'A scan for this dose did not match in the last 15 minutes. Check the patient and the medicine, then scan the medicine code.';
      expect(classifyGiveError(apiError(409, 'override-after-mismatch', detail))).toEqual({
        kind: 'override-refused',
        message: detail,
      });
    });

    it('409 override-after-mismatch without a detail uses the contract detail', () => {
      const f = classifyGiveError(apiError(409, 'override-after-mismatch', ''));
      expect(f.kind).toBe('override-refused');
      expect(OVERRIDE_AFTER_MISMATCH).toContain('did not match in the last 15 minutes');
    });

    it('409 dose-not-due reloads the MAR (also for a PRN dose outside its start and end)', () => {
      expect(classifyGiveError(apiError(409, 'dose-not-due', 'This dose cannot be given now. Reload the page.'))).toEqual({
        kind: 'reload',
        message: 'This dose cannot be given now. Reload the page.',
      });
    });

    it('409 client-key-reused asks for a new key and shows the contract text', () => {
      expect(
        classifyGiveError(apiError(409, 'client-key-reused', 'This dose was already recorded with different values. Reload the page.')),
      ).toEqual({ kind: 'key-reused', message: 'This dose was already recorded with different values. Reload the page.' });
    });

    it('400 on the wristband: the field text "Scan the wristband first."', () => {
      const f = classifyGiveError(
        apiError(400, 'validation', 'Scan the wristband first.', {
          fieldErrors: [{ field: 'wristband', message: 'Scan the wristband first.' }],
        }),
      );
      expect(f).toEqual({
        kind: 'fields',
        errors: { wristband: 'Scan the wristband first.' },
        message: 'Scan the wristband first.',
      });
    });

    it('409 prn-too-soon and prn-max-reached keep the sheet and say when', () => {
      expect(classifyGiveError(apiError(409, 'prn-too-soon', 'Too soon. Next dose allowed at 2:30 PM.'))).toEqual({
        kind: 'prn-lock',
        message: 'Too soon. Next dose allowed at 2:30 PM.',
      });
      expect(
        classifyGiveError(apiError(409, 'prn-max-reached', 'Daily maximum reached. Next dose allowed at 6:10 PM.')).kind,
      ).toBe('prn-lock');
    });

    it('409 order-changed and order-on-hold reload the MAR', () => {
      expect(
        classifyGiveError(apiError(409, 'order-changed', 'This order was changed. Reload and check the new order.')).kind,
      ).toBe('reload');
      expect(classifyGiveError(apiError(409, 'order-on-hold', 'This order is on hold. Do not give it.')).kind).toBe('reload');
    });

    it('400 on the override, medicine code and note fields', () => {
      const f = classifyGiveError(
        apiError(400, 'validation', 'Choose why you cannot scan.', {
          fieldErrors: [
            { field: 'medicineCode', message: 'Scan or type the medicine code.' },
            { field: 'override.reasonKey', message: 'Choose why you cannot scan.' },
            { field: 'override.note', message: 'Write what you checked instead.' },
            { field: 'note', message: 'Write what happened.' },
          ],
        }),
      );
      expect(f).toMatchObject({
        kind: 'fields',
        errors: {
          medicineCode: 'Scan or type the medicine code.',
          overrideReason: 'Choose why you cannot scan.',
          overrideNote: 'Write what you checked instead.',
          note: 'Write what happened.',
        },
      });
    });

    it('400 on the order version reloads', () => {
      expect(
        classifyGiveError(
          apiError(400, 'validation', 'Reload the page and try again.', {
            fieldErrors: [{ field: 'orderVersion', message: 'Reload the page and try again.' }],
          }),
        ).kind,
      ).toBe('reload');
    });
  });

  describe('recorded in error', () => {
    const struck = doseOf({
      correction: {
        kind: 'IN_ERROR',
        reason: { key: 'DUPLICATE', label: 'Duplicate entry' },
        correctedByName: 'Sr. Rupali More',
        correctedAt: '2026-11-06T05:50:00Z',
      },
    });

    it('the history line keeps the original apart from the correction', () => {
      expect(historyLine(struck)).toEqual({
        original: 'Given 8:04 AM by Sr. Anjali Pawar',
        note: 'recorded in error by Sr. Rupali More at 11:20 AM: Duplicate entry',
      });
    });

    it('the panel line names the way the dose was given and the second nurse', () => {
      expect(doseDetailLine(doseOf())).toBe(
        'Given 8:04 AM by Sr. Anjali Pawar · wristband and medicine scanned · checked by Sr. Rupali More',
      );
      expect(
        doseDetailLine(
          doseOf({ method: 'OVERRIDE', secondCheckByName: null, overrideReason: { key: 'SCANNER_DOWN', label: 'Scanner not working' } }),
        ),
      ).toBe('Given 8:04 AM by Sr. Anjali Pawar · scan override: Scanner not working');
    });
  });
});
