import { ApiError, ProblemDetail } from '../../../core/http/problem-detail';
import { DoseRow } from './mar.model';
import { AT_2PM, AT_8AM, AT_8PM, GIVEN_DOSE, MAR_AS_OF, marOf, orderOf, slotOf } from './mar.fixtures';
import {
  GIVE_ERROR,
  NURSING_TABS,
  COLLEAGUE_PIN_LIMIT,
  classifyGiveError,
  clockText,
  countBadge,
  giveSubtitle,
  giveTitle,
  givenInput,
  givenToast,
  highAlertPill,
  highAlertStepTitle,
  lastDoseLine,
  lastWord,
  marOrderLine,
  marSubtitle,
  mismatchLine,
  mismatchToast,
  notGivenInput,
  notGivenToast,
  notGivingStepTitle,
  requestSignature,
  slotChip,
  slotWhen,
  tabAfterKey,
} from './mar.model';

const apiError = (status: number, slug: string, detail: string, extra: Partial<ProblemDetail> = {}) =>
  new ApiError({ type: `https://sahaj.in/problems/${slug}`, title: slug, status, detail, ...extra });

describe('MAR model', () => {
  describe('clockText (mockup sfHm, IST)', () => {
    it('reads whole hours without minutes', () => {
      expect(clockText(AT_8AM)).toBe('8 AM');
      expect(clockText(AT_2PM)).toBe('2 PM');
      expect(clockText(AT_8PM)).toBe('8 PM');
    });

    it('keeps minutes when they are not 0', () => {
      expect(clockText('2026-11-06T03:00:00Z')).toBe('8:30 AM');
      expect(clockText('2026-11-06T02:34:00Z')).toBe('8:04 AM');
      expect(clockText(MAR_AS_OF)).toBe('11:05 AM');
    });

    it('handles midnight and noon', () => {
      expect(clockText('2026-11-05T18:30:00Z')).toBe('12 AM');
      expect(clockText('2026-11-05T18:50:00Z')).toBe('12:20 AM');
      expect(clockText('2026-11-06T06:30:00Z')).toBe('12 PM');
      expect(clockText('2026-11-06T07:00:00Z')).toBe('12:30 PM');
    });

    it('uses IST whatever the device zone is', () => {
      // 20:00 UTC is 1:30 AM the next day in IST.
      expect(clockText('2026-11-06T20:00:00Z')).toBe('1:30 AM');
    });

    it('is empty for a missing or bad instant', () => {
      expect(clockText(null)).toBe('');
      expect(clockText(undefined)).toBe('');
      expect(clockText('not a date')).toBe('');
    });
  });

  describe('slotWhen', () => {
    it('is the time alone on the day of asOf', () => {
      expect(slotWhen(AT_8AM, MAR_AS_OF)).toBe('8 AM');
    });

    it('adds the day and month on another IST day', () => {
      expect(slotWhen('2026-10-24T14:30:00Z', MAR_AS_OF)).toBe('24 Oct, 8 PM');
    });

    it('compares IST days, not UTC days', () => {
      // 11 PM IST on 5 Nov is 17:30Z on 5 Nov; asOf 12:30 AM IST on 6 Nov is 19:00Z on 5 Nov.
      expect(slotWhen('2026-11-05T17:30:00Z', '2026-11-05T19:00:00Z')).toBe('5 Nov, 11 PM');
      expect(slotWhen('2026-11-05T17:30:00Z', '2026-11-05T18:00:00Z')).toBe('11 PM');
    });
  });

  it('lastWord is the last word of a name', () => {
    expect(lastWord('Sr. Anjali Pawar')).toBe('Pawar');
    expect(lastWord('  Pawar ')).toBe('Pawar');
    expect(lastWord('')).toBe('');
  });

  describe('texts of the MAR tab', () => {
    it('subtitle: bed, medicine round, today and the time of asOf', () => {
      expect(marSubtitle(marOf())).toBe('Bed G-03 · medicine round, today 11:05 AM');
    });

    it('small line: route, frequency, ordering doctor', () => {
      expect(marOrderLine(orderOf())).toBe('Oral · Twice daily · Dr. Meera Kulkarni');
    });

    it('high-alert pill', () => {
      expect(highAlertPill({ highAlert: 'Anticoagulant' })).toBe('High-alert · Anticoagulant');
    });
  });

  describe('slotChip: one chip per state', () => {
    it('GIVEN: check, time, "<time recorded> · <last name>" and the hint with the second nurse', () => {
      const chip = slotChip(slotOf({ state: 'GIVEN', canGive: false, dose: GIVEN_DOSE }));
      expect(chip).toEqual({
        kind: 'done',
        tone: 'ok',
        time: '8 AM',
        small: '8:04 AM · Pawar',
        hint: 'Given by Sr. Anjali Pawar, checked by Sr. Rupali More',
      });
    });

    it('GIVEN without a second nurse has no "checked by"', () => {
      const dose: DoseRow = { ...GIVEN_DOSE, secondCheckByName: null };
      expect(slotChip(slotOf({ state: 'GIVEN', dose })).hint).toBe('Given by Sr. Anjali Pawar');
    });

    it('HELD: "Not given · <reason>"', () => {
      const dose: DoseRow = {
        ...GIVEN_DOSE,
        outcome: 'HELD',
        reason: { key: 'NIL_BY_MOUTH', label: 'Nil by mouth' },
        secondCheckByName: null,
      };
      expect(slotChip(slotOf({ state: 'HELD', dose }))).toMatchObject({
        kind: 'static',
        tone: 'grey',
        small: 'Not given · Nil by mouth',
      });
    });

    it('REFUSED: "Refused · <reason>"', () => {
      const dose: DoseRow = {
        ...GIVEN_DOSE,
        outcome: 'REFUSED',
        reason: { key: 'PATIENT_REFUSED', label: 'Patient refused' },
        secondCheckByName: null,
      };
      expect(slotChip(slotOf({ state: 'REFUSED', dose })).small).toBe('Refused · Patient refused');
    });

    it('NOT_RECORDED, WAITING, BEFORE_START and LATER are grey, not clickable, with their words', () => {
      expect(slotChip(slotOf({ state: 'NOT_RECORDED' }))).toMatchObject({ kind: 'static', tone: 'grey', small: 'Not recorded' });
      expect(slotChip(slotOf({ state: 'WAITING' }))).toMatchObject({ kind: 'static', tone: 'grey', small: 'Waiting for pharmacist' });
      expect(slotChip(slotOf({ state: 'BEFORE_START' }))).toMatchObject({
        kind: 'static',
        tone: 'grey',
        small: 'Before the order started',
      });
      expect(slotChip(slotOf({ state: 'LATER' }))).toMatchObject({ kind: 'static', tone: 'later', small: 'Later today' });
    });

    it('DUE: amber button "Due now · Give"', () => {
      expect(slotChip(slotOf({ state: 'DUE' }))).toMatchObject({ kind: 'button', tone: 'warn', small: 'Due now · Give' });
    });

    it('LATE: red button "<n> h late · Give", the hours as the server counted them', () => {
      expect(slotChip(slotOf({ state: 'LATE', lateHours: 1 }))).toMatchObject({ kind: 'button', tone: 'crit', small: '1 h late · Give' });
      expect(slotChip(slotOf({ state: 'LATE', lateHours: 3 })).small).toBe('3 h late · Give');
    });

    it('LATE never says "min late" (that form can not occur)', () => {
      expect(slotChip(slotOf({ state: 'LATE', lateHours: 1 })).small).not.toContain('min');
    });
  });

  describe('Give sheet texts', () => {
    it('title', () => {
      expect(giveTitle(orderOf())).toBe('Give · Tab. Paracetamol 650 mg');
    });

    it('subtitle: time, route, frequency', () => {
      expect(giveSubtitle(orderOf(), slotOf({ state: 'DUE' }))).toBe('8 AM dose · Oral · Twice daily');
    });

    it('subtitle adds the hours late for a late slot only', () => {
      expect(giveSubtitle(orderOf(), slotOf({ state: 'LATE', lateHours: 1 }))).toBe('8 AM dose · Oral · Twice daily · 1 h late');
      expect(giveSubtitle(orderOf(), slotOf({ slotAt: AT_2PM, state: 'LATE', lateHours: 2 }))).toBe(
        '2 PM dose · Oral · Twice daily · 2 h late',
      );
    });

    it('step titles: the high-alert class in lower case, and the last step number', () => {
      expect(highAlertStepTitle({ highAlert: 'Concentrated electrolyte' })).toBe(
        '2 · Second nurse check · high-alert concentrated electrolyte',
      );
      expect(notGivingStepTitle(false)).toBe('2 · If you are not giving it');
      expect(notGivingStepTitle(true)).toBe('3 · If you are not giving it');
    });

    it('toasts', () => {
      expect(givenToast('Tab. Aspirin 75 mg', 'Ramesh Kale')).toBe('Tab. Aspirin 75 mg recorded as given to Ramesh Kale');
      expect(notGivenToast('Tab. Aspirin 75 mg', 'Nil by mouth')).toBe(
        'Recorded: Tab. Aspirin 75 mg not given · Nil by mouth. The doctor sees this on the chart.',
      );
      expect(mismatchToast('Ramesh Kale')).toBe(
        'Wristband does not match Ramesh Kale. Stop. Check the patient before giving anything.',
      );
      expect(mismatchLine('Ramesh Kale')).toBe("Wrong patient. This band is not Ramesh Kale's.");
    });
  });

  describe('tabs', () => {
    it('lists the two tabs in order', () => {
      expect(NURSING_TABS).toEqual(['vit', 'mar']);
    });

    it('arrows wrap, Home and End jump, other keys are ignored', () => {
      expect(tabAfterKey('ArrowRight', 'vit')).toBe('mar');
      expect(tabAfterKey('ArrowRight', 'mar')).toBe('vit');
      expect(tabAfterKey('ArrowLeft', 'vit')).toBe('mar');
      expect(tabAfterKey('ArrowLeft', 'mar')).toBe('vit');
      expect(tabAfterKey('Home', 'mar')).toBe('vit');
      expect(tabAfterKey('End', 'vit')).toBe('mar');
      expect(tabAfterKey('Enter', 'vit')).toBeNull();
      expect(tabAfterKey('ArrowDown', 'vit')).toBeNull();
    });

    it('the count shows only above 0', () => {
      expect(countBadge(0)).toBeNull();
      expect(countBadge(undefined)).toBeNull();
      expect(countBadge(null)).toBeNull();
      expect(countBadge(1)).toBe(1);
      expect(countBadge(12)).toBe(12);
    });
  });

  describe('request bodies', () => {
    const slot = { slotAt: AT_8AM };
    const entered = { wristband: ' pt-00042 ', said: true, nurse: 'm-rupali', pin: '4821' };

    it('Given without high-alert sends the box and the statement, no second nurse and no PIN', () => {
      const body = givenInput('k1', slot, false, entered);
      expect(body).toEqual({
        clientKey: 'k1',
        slotAt: AT_8AM,
        outcome: 'GIVEN',
        wristband: ' pt-00042 ',
        identityConfirmed: true,
      });
      expect(Object.keys(body)).not.toContain('secondNursePin');
      expect(Object.keys(body)).not.toContain('secondNurseId');
    });

    it('Given high-alert adds the second nurse and the PIN', () => {
      expect(givenInput('k1', slot, true, entered)).toMatchObject({ secondNurseId: 'm-rupali', secondNursePin: '4821' });
    });

    it('Given high-alert with no nurse chosen sends null (the server answers)', () => {
      expect(givenInput('k1', slot, true, { ...entered, nurse: '' }).secondNurseId).toBeNull();
    });

    it('Record not given sends the reason only: no wristband, statement, nurse or PIN', () => {
      expect(notGivenInput('k2', slot, 'NIL_BY_MOUTH')).toEqual({
        clientKey: 'k2',
        slotAt: AT_8AM,
        outcome: 'NOT_GIVEN',
        reasonKey: 'NIL_BY_MOUTH',
      });
      expect(notGivenInput('k2', slot, '').reasonKey).toBeNull();
    });

    it('the signature holds what the server compares on a repeat', () => {
      const a = requestSignature(givenInput('k', slot, true, entered));
      expect(requestSignature(givenInput('k', slot, true, { ...entered, pin: '0000', wristband: 'x' }))).toBe(a);
      expect(requestSignature(givenInput('k', slot, true, { ...entered, nurse: 'm-nikhil' }))).not.toBe(a);
      expect(requestSignature(notGivenInput('k', slot, 'NIL_BY_MOUTH'))).not.toBe(a);
      expect(requestSignature(notGivenInput('k', slot, 'NIL_BY_MOUTH'))).not.toBe(
        requestSignature(notGivenInput('k', slot, 'HELD_DOCTOR')),
      );
    });
  });

  describe('classifyGiveError', () => {
    it('400 on a bedside field: shown under that field, with the detail for the toast', () => {
      const f = classifyGiveError(
        apiError(400, 'validation', 'Scan the wristband first.', {
          fieldErrors: [{ field: 'wristband', message: 'Scan the wristband first.' }],
        }),
      );
      expect(f).toEqual({ kind: 'fields', errors: { wristband: 'Scan the wristband first.' }, message: 'Scan the wristband first.' });
    });

    it('400 on the statement, the nurse and the reason', () => {
      expect(
        classifyGiveError(
          apiError(400, 'validation', 'x', { fieldErrors: [{ field: 'identityConfirmed', message: 'Confirm the second identifier: name and date of birth.' }] }),
        ),
      ).toMatchObject({ kind: 'fields', errors: { identityConfirmed: 'Confirm the second identifier: name and date of birth.' } });
      expect(
        classifyGiveError(apiError(400, 'validation', 'x', { fieldErrors: [{ field: 'secondNurseId', message: 'The second check must be a different nurse.' }] })),
      ).toMatchObject({ errors: { secondNurseId: 'The second check must be a different nurse.' } });
      expect(
        classifyGiveError(apiError(400, 'validation', 'x', { fieldErrors: [{ field: 'reasonKey', message: 'Choose why the dose is not given.' }] })),
      ).toMatchObject({ errors: { reasonKey: 'Choose why the dose is not given.' } });
    });

    it('400 with the high-alert message on both fields shows it once, under the PIN', () => {
      const message = 'High-alert medicine: a second nurse must check it and enter a 4-digit PIN.';
      const f = classifyGiveError(
        apiError(400, 'validation', message, {
          fieldErrors: [
            { field: 'secondNurseId', message },
            { field: 'secondNursePin', message },
          ],
        }),
      );
      expect(f).toEqual({ kind: 'fields', errors: { secondNursePin: message }, message });
    });

    it('400 on slotAt means the MAR is out of date: close and reload', () => {
      const f = classifyGiveError(
        apiError(400, 'validation', 'This is not a dose time of this medicine today. Reload the page and try again.', {
          fieldErrors: [{ field: 'slotAt', message: 'This is not a dose time of this medicine today. Reload the page and try again.' }],
        }),
      );
      expect(f.kind).toBe('reload');
    });

    it('400 on clientKey makes a new key', () => {
      const f = classifyGiveError(apiError(400, 'validation', 'x', { fieldErrors: [{ field: 'clientKey', message: 'Reload the page and try again.' }] }));
      expect(f).toEqual({ kind: 'key-reused', message: 'Reload the page and try again.' });
    });

    it('400 on no known field is a notice', () => {
      expect(classifyGiveError(apiError(400, 'validation', 'Check the details.'))).toEqual({
        kind: 'notice',
        message: 'Check the details.',
        lost: false,
      });
    });

    it('403 wrong-pin: under Their PIN, focus on the PIN, never a count of tries left, no ward line', () => {
      expect(classifyGiveError(apiError(403, 'wrong-pin', 'That PIN is not right. 2 tries left.'))).toEqual({
        kind: 'pin',
        message: 'That PIN is not right.',
        focus: 'pin',
        ward: false,
      });
    });

    it('403 pin-locked: under Their PIN, focus on the nurse list (choose another)', () => {
      expect(classifyGiveError(apiError(403, 'pin-locked', "The second nurse's PIN is locked after 5 wrong tries."))).toMatchObject({
        kind: 'pin',
        focus: 'nurse',
      });
    });

    it('409 pin-not-set is the second nurse state, under Their PIN', () => {
      expect(
        classifyGiveError(apiError(409, 'pin-not-set', 'The second nurse has not set a PIN yet. Choose another nurse.')),
      ).toEqual({ kind: 'pin', message: 'The second nurse has not set a PIN yet. Choose another nurse.', focus: 'nurse', ward: true });
    });

    describe('the ward line (SAH-38, ADR-014 D10)', () => {
      const ward = (status: number, slug: string) => {
        const f = classifyGiveError(apiError(status, slug, 'x'));
        return f.kind === 'pin' ? f.ward : 'not a pin failure';
      };

      it('is for pin-locked, pin-not-set, rate-limit and colleague-pin-limit', () => {
        expect(ward(403, 'pin-locked')).toBe(true);
        expect(ward(409, 'pin-not-set')).toBe(true);
        expect(ward(429, 'rate-limit')).toBe(true);
        expect(ward(429, 'colleague-pin-limit')).toBe(true);
      });

      it('is not for wrong-pin, pin-unavailable or pin-check-failed', () => {
        expect(ward(403, 'wrong-pin')).toBe(false);
        expect(ward(503, 'pin-unavailable')).toBe(false);
        expect(ward(503, 'pin-check-failed')).toBe(false);
      });
    });

    it('429 colleague-pin-limit: T1 under Their PIN, focus on the nurse list, no retry time', () => {
      expect(
        classifyGiveError(apiError(429, 'colleague-pin-limit', 'Too many wrong PIN tries for this nurse. Choose another nurse.')),
      ).toEqual({
        kind: 'pin',
        message: 'Too many wrong PIN tries for this nurse. Choose another nurse.',
        focus: 'nurse',
        ward: true,
      });
    });

    it('429 colleague-pin-limit without a detail still says T1', () => {
      const f = classifyGiveError(apiError(429, 'colleague-pin-limit', undefined as unknown as string));
      expect(f).toMatchObject({ kind: 'pin', message: COLLEAGUE_PIN_LIMIT });
    });

    it('503 pin-check-failed: T7, try again, focus on the PIN (not a lost answer: nothing was recorded)', () => {
      expect(classifyGiveError(apiError(503, 'pin-check-failed', 'We could not check the PIN. Try again.'))).toEqual({
        kind: 'pin',
        message: 'We could not check the PIN. Try again.',
        focus: 'pin',
        ward: false,
      });
    });

    it('429 and 503 are PIN refusals too', () => {
      expect(classifyGiveError(apiError(429, 'rate-limit', 'Too many PIN tries in a short time. Try again in a few minutes.'))).toMatchObject({ kind: 'pin' });
      expect(classifyGiveError(apiError(503, 'pin-unavailable', 'PIN checks are not available right now. Try again later.'))).toMatchObject({ kind: 'pin' });
    });

    it('a 503 pin-unavailable is a PIN refusal, any other 503 is a lost answer', () => {
      expect(classifyGiveError(apiError(503, 'pin-unavailable', 'x')).kind).toBe('pin');
      expect(classifyGiveError(apiError(503, 'unavailable', 'x'))).toEqual({ kind: 'notice', message: GIVE_ERROR, lost: true });
    });

    it('409 wristband-mismatch: the client builds the texts (no name in the problem)', () => {
      expect(classifyGiveError(apiError(409, 'wristband-mismatch', 'Wristband does not match this patient. Stop.'))).toEqual({ kind: 'mismatch' });
    });

    for (const slug of [
      'dose-recorded',
      'order-state',
      'order-not-verified',
      'dose-not-due',
      'dose-before-start',
      'not-admitted',
      'ipd-readonly',
    ]) {
      it(`409 ${slug}: close the sheet and reload the MAR`, () => {
        expect(classifyGiveError(apiError(409, slug, 'Server text.'))).toEqual({ kind: 'reload', message: 'Server text.' });
      });
    }

    it('409 client-key-reused: new key, the sheet stays', () => {
      expect(classifyGiveError(apiError(409, 'client-key-reused', 'This dose was already recorded with different values. Reload the page.'))).toEqual({
        kind: 'key-reused',
        message: 'This dose was already recorded with different values. Reload the page.',
      });
    });

    it('403 forbidden, 404 and an unknown 409 are notices that keep the sheet', () => {
      expect(classifyGiveError(apiError(403, 'forbidden', 'Only nurses and the clinic owner can give medicines.'))).toEqual({
        kind: 'notice',
        message: 'Only nurses and the clinic owner can give medicines.',
        lost: false,
      });
      expect(classifyGiveError(apiError(404, 'not-found', 'Not found.'))).toMatchObject({ kind: 'notice', lost: false });
      expect(classifyGiveError(apiError(409, 'something-else', 'Other.'))).toMatchObject({ kind: 'notice', lost: false });
    });

    it('5xx, no connection and non-API errors are a lost answer with the fixed text', () => {
      expect(classifyGiveError(apiError(500, 'internal', 'boom'))).toEqual({ kind: 'notice', message: GIVE_ERROR, lost: true });
      expect(classifyGiveError(apiError(0, 'network', 'offline'))).toEqual({ kind: 'notice', message: GIVE_ERROR, lost: true });
      expect(classifyGiveError(new Error('x'))).toEqual({ kind: 'notice', message: GIVE_ERROR, lost: true });
    });
  });

  describe('lastDoseLine (EMR, OQ-32-4)', () => {
    it('is null when no dose was recorded', () => {
      expect(lastDoseLine(null, MAR_AS_OF)).toBeNull();
    });

    it('Given: slot, time recorded and the last name; not a warning', () => {
      expect(lastDoseLine(GIVEN_DOSE, MAR_AS_OF)).toEqual({ text: 'Last dose 8 AM: Given · 8:04 AM · Pawar', warn: false });
    });

    it('Refused and Not given read in the warn colour with the reason', () => {
      const refused: DoseRow = { ...GIVEN_DOSE, outcome: 'REFUSED', reason: { key: 'PATIENT_REFUSED', label: 'Patient refused' } };
      const held: DoseRow = { ...GIVEN_DOSE, outcome: 'HELD', reason: { key: 'NIL_BY_MOUTH', label: 'Nil by mouth' } };
      expect(lastDoseLine(refused, MAR_AS_OF)).toEqual({ text: 'Last dose 8 AM: Refused · Patient refused', warn: true });
      expect(lastDoseLine(held, MAR_AS_OF)).toEqual({ text: 'Last dose 8 AM: Not given · Nil by mouth', warn: true });
    });

    it('a slot of another day shows the day and month', () => {
      const old: DoseRow = { ...GIVEN_DOSE, slotAt: '2026-10-24T14:30:00Z' };
      expect(lastDoseLine(old, MAR_AS_OF)!.text).toBe('Last dose 24 Oct, 8 PM: Given · 8:04 AM · Pawar');
    });
  });
});
