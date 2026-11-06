import {
  EMPTY_DRAFT,
  FORMAT_MESSAGE,
  LatestVitals,
  NursingSheet,
  VitalsDraft,
  VitalsSaved,
  agoText,
  ewsNotice,
  noteAgoText,
  noteLength,
  prefill,
  sameAsLast,
  savedToast,
  subtitleText,
  toVitalsInput,
  validateVitals,
} from './nursing.model';

const AS_OF = '2026-11-05T10:00:00Z';
const minutesBefore = (m: number): string => new Date(new Date(AS_OF).getTime() - m * 60000).toISOString();

const LATEST: LatestVitals = {
  id: 'v1',
  bp: '120/80',
  bpSystolic: 120,
  bpDiastolic: 80,
  pulse: 80,
  spo2: 98,
  tempC: 37,
  rr: 16,
  ewsScore: 0,
  ewsRisk: 'LOW',
  recordedAt: minutesBefore(35),
  recordedByName: 'Sr. Anjali Pawar',
};

const SHEET: NursingSheet = {
  admissionId: 'a1',
  patientName: 'Asha Bendre',
  bedCode: '202',
  status: 'ADMITTED',
  state: 'on',
  canRecord: true,
  readOnlyReason: null,
  asOf: AS_OF,
  latest: LATEST,
  vitalsDue: false,
  doseCount: 0,
  recentNotes: [],
};

const VALID: VitalsDraft = { bp: '120/80', pulse: '80', spo2: '98', temp: '37.0', rr: '16', note: '' };

describe('nursing model', () => {
  describe('agoText (mockup pcAgo, from the server clock)', () => {
    it('reads minutes under an hour', () => {
      expect(agoText(AS_OF, minutesBefore(0))).toBe('0 min ago');
      expect(agoText(AS_OF, minutesBefore(35))).toBe('35 min ago');
      expect(agoText(AS_OF, minutesBefore(59))).toBe('59 min ago');
    });

    it('reads hours and minutes from an hour, no days', () => {
      expect(agoText(AS_OF, minutesBefore(60))).toBe('1 h ago');
      expect(agoText(AS_OF, minutesBefore(65))).toBe('1 h 5 min ago');
      expect(agoText(AS_OF, minutesBefore(125))).toBe('2 h 5 min ago');
      expect(agoText(AS_OF, minutesBefore(30 * 60))).toBe('30 h ago');
    });

    it('rounds seconds down and takes a negative difference as 0', () => {
      expect(agoText(AS_OF, '2026-11-05T09:59:01Z')).toBe('0 min ago');
      expect(agoText(AS_OF, '2026-11-05T10:05:00Z')).toBe('0 min ago');
    });
  });

  it('writes the subtitle with and without vitals', () => {
    expect(subtitleText(SHEET)).toBe('Bed 202 · last vitals 35 min ago');
    expect(subtitleText({ ...SHEET, latest: null })).toBe('Bed 202');
  });

  it('writes a note line from asOf', () => {
    const note = { id: 'n1', text: 'x', recordedAt: minutesBefore(95), recordedByName: 'Sr. Anjali Pawar' };
    expect(noteAgoText(SHEET, note)).toBe('1 h 35 min ago · Sr. Anjali Pawar');
  });

  describe('score notice', () => {
    it('has none without vitals', () => {
      expect(ewsNotice(null)).toBeNull();
    });

    it('is green for Low, amber for Medium and red for High with the contract wording', () => {
      expect(ewsNotice({ ...LATEST, ewsScore: 2, ewsRisk: 'LOW' })).toEqual({
        tone: 'good',
        strong: '2 · Low',
        advice: '. Routine vitals every 4 hours.',
      });
      expect(ewsNotice({ ...LATEST, ewsScore: 5, ewsRisk: 'MEDIUM' })).toEqual({
        tone: 'warn',
        strong: '5 · Medium',
        advice: '. Repeat vitals within 1 hour.',
      });
      expect(ewsNotice({ ...LATEST, ewsScore: 8, ewsRisk: 'HIGH' })).toEqual({
        tone: 'crit',
        strong: '8 · High',
        advice: '. Inform the doctor now and repeat vitals every 30 minutes.',
      });
    });
  });

  describe('prefill', () => {
    it('is empty without vitals', () => {
      expect(prefill(null)).toEqual(EMPTY_DRAFT);
    });

    it('fills the five fields, temperature with one decimal, and leaves the note empty', () => {
      expect(prefill(LATEST)).toEqual({ bp: '120/80', pulse: '80', spo2: '98', temp: '37.0', rr: '16', note: '' });
      expect(prefill({ ...LATEST, tempC: 38.6 }).temp).toBe('38.6');
    });
  });

  describe('sameAsLast', () => {
    it('is true when the five values are the last recorded values, whatever the note says', () => {
      expect(sameAsLast({ ...VALID, note: 'text' }, LATEST)).toBe(true);
      expect(sameAsLast({ ...VALID, pulse: ' 80 ' }, LATEST)).toBe(true);
    });

    it('is false when any one of the five differs', () => {
      for (const key of ['bp', 'pulse', 'spo2', 'temp', 'rr'] as const) {
        expect(sameAsLast({ ...VALID, [key]: key === 'bp' ? '121/80' : '99' }, LATEST), key).toBe(false);
      }
      expect(sameAsLast({ ...VALID, temp: '37.1' }, LATEST)).toBe(false);
    });

    it('compares values, not text: 37 is 37.0 and 080 is 80', () => {
      expect(sameAsLast({ ...VALID, temp: '37' }, LATEST)).toBe(true);
      expect(sameAsLast({ ...VALID, pulse: '080' }, LATEST)).toBe(true);
    });

    it('is false when a value is not a value', () => {
      expect(sameAsLast({ ...VALID, pulse: 'abc' }, LATEST)).toBe(false);
      expect(sameAsLast({ ...VALID, bp: '120' }, LATEST)).toBe(false);
    });

    it('is false without a last reading', () => {
      expect(sameAsLast(VALID, null)).toBe(false);
    });
  });

  describe('validateVitals (same patterns as VitalsInput)', () => {
    it('accepts the placeholders', () => {
      expect(validateVitals(VALID)).toEqual({});
    });

    it('trims spaces at both ends', () => {
      expect(validateVitals({ ...VALID, bp: ' 120/80 ', pulse: ' 80 ', temp: ' 37.5 ' })).toEqual({});
    });

    it('gives the format message for a missing or malformed value', () => {
      const e = validateVitals(EMPTY_DRAFT);
      for (const key of ['bp', 'pulse', 'spo2', 'temp', 'rr'] as const) {
        expect(e[key]).toBe(FORMAT_MESSAGE);
      }
      expect(FORMAT_MESSAGE).toBe('Enter all five vitals. BP as 120/80.');
    });

    it('refuses a BP that is not two whole numbers of 2 or 3 digits', () => {
      for (const bp of ['120', '120/', '/80', '1/80', '1200/80', '120 / 80', '120/80/70', '12.5/80', 'abc']) {
        expect(validateVitals({ ...VALID, bp }).bp, bp).toBe(FORMAT_MESSAGE);
      }
    });

    it('refuses zero, signs, decimals and long values for the whole-number fields', () => {
      for (const v of ['0', '000', '-5', '8.5', '1000', ' ', '8 0']) {
        expect(validateVitals({ ...VALID, pulse: v }).pulse, v).toBe(FORMAT_MESSAGE);
      }
    });

    it('refuses a temperature that is zero or has more than one decimal', () => {
      for (const t of ['0', '0.0', '37.55', '370.5', '.5', '37.', '3e1']) {
        expect(validateVitals({ ...VALID, temp: t }).temp, t).toBe(FORMAT_MESSAGE);
      }
      expect(validateVitals({ ...VALID, temp: '37' }).temp).toBeUndefined();
    });

    it('refuses implausible values with the contract messages, bounds inclusive', () => {
      const bpMessage = 'Check the BP. Systolic 40 to 300, diastolic 20 to 200.';
      expect(validateVitals({ ...VALID, bp: '39/80' }).bp).toBe(bpMessage);
      expect(validateVitals({ ...VALID, bp: '40/20' }).bp).toBeUndefined();
      expect(validateVitals({ ...VALID, bp: '300/200' }).bp).toBeUndefined();
      expect(validateVitals({ ...VALID, bp: '301/80' }).bp).toBe(bpMessage);
      expect(validateVitals({ ...VALID, bp: '120/19' }).bp).toBe(bpMessage);
      expect(validateVitals({ ...VALID, bp: '120/201' }).bp).toBe(bpMessage);

      expect(validateVitals({ ...VALID, pulse: '300' }).pulse).toBeUndefined();
      expect(validateVitals({ ...VALID, pulse: '301' }).pulse).toBe('Check the pulse. Enter 1 to 300 per minute.');
      expect(validateVitals({ ...VALID, spo2: '100' }).spo2).toBeUndefined();
      expect(validateVitals({ ...VALID, spo2: '101' }).spo2).toBe('Check the SpO₂. Enter 1 to 100 %.');
      expect(validateVitals({ ...VALID, rr: '301' }).rr).toBe('Check the RR. Enter 1 to 300 per minute.');

      const tempMessage = 'Check the temperature. Enter 25.0 to 45.0 °C.';
      expect(validateVitals({ ...VALID, temp: '24.9' }).temp).toBe(tempMessage);
      expect(validateVitals({ ...VALID, temp: '25.0' }).temp).toBeUndefined();
      expect(validateVitals({ ...VALID, temp: '45.0' }).temp).toBeUndefined();
      expect(validateVitals({ ...VALID, temp: '45.1' }).temp).toBe(tempMessage);
      expect(validateVitals({ ...VALID, temp: '98.6' }).temp).toBe(tempMessage);
    });

    it('does not refuse a systolic at or below the diastolic', () => {
      expect(validateVitals({ ...VALID, bp: '80/120' }).bp).toBeUndefined();
    });

    it('allows a note of 1,000 characters and refuses 1,001', () => {
      expect(validateVitals({ ...VALID, note: 'a'.repeat(1000) }).note).toBeUndefined();
      expect(validateVitals({ ...VALID, note: 'a'.repeat(1001) }).note).toBe('Keep the note to 1,000 characters.');
    });

    it('counts code points, counts a line break as one and strips the ends', () => {
      expect(noteLength('😀'.repeat(1000))).toBe(1000);
      expect(noteLength('a\r\nb')).toBe(3);
      expect(noteLength('  abc \n ')).toBe(3);
    });
  });

  it('builds the request body from the typed strings with no score', () => {
    const body = toVitalsInput({ ...VALID, bp: ' 120/80 ', note: 'Line 1\nLine 2' }, 'key-1');
    expect(body).toEqual({
      clientKey: 'key-1',
      bp: '120/80',
      pulse: '80',
      spo2: '98',
      temp: '37.0',
      rr: '16',
      note: 'Line 1\nLine 2',
    });
    expect(Object.keys(body)).not.toContain('ewsScore');
  });

  it('sends no note when the note is blank', () => {
    expect(toVitalsInput({ ...VALID, note: '  \n ' }, 'k').note).toBeNull();
  });

  describe('savedToast', () => {
    const answer = (
      risk: VitalsSaved['saved']['ewsRisk'],
      score: number,
      target: 15 | 60 | null,
    ): VitalsSaved => ({
      ...SHEET,
      saved: { vitalsId: 'v', ewsScore: score, ewsRisk: risk, recordedAt: AS_OF, noteAdded: false, replayed: false },
      escalation: {
        outcome: target ? 'RAISED' : 'NONE',
        open:
          target && risk !== 'LOW'
            ? { id: 'e1', ewsScore: score, ewsRisk: risk, targetMinutes: target, status: 'OPEN', raisedAt: AS_OF }
            : null,
      },
    });

    it('says Vitals saved for Low', () => {
      expect(savedToast(answer('LOW', 2, null))).toBe('Vitals saved for Asha Bendre · early warning 2 (low)');
    });

    it('says Escalation raised with 1 hour for Medium and 15 min for High', () => {
      expect(savedToast(answer('MEDIUM', 5, 60))).toBe(
        'Escalation raised for Asha Bendre · EWS 5 · doctor review within 1 hour. Tell the doctor now: no alert is sent.',
      );
      expect(savedToast(answer('HIGH', 8, 15))).toBe(
        'Escalation raised for Asha Bendre · EWS 8 · doctor review within 15 min. Tell the doctor now: no alert is sent.',
      );
    });

    it('falls back to Vitals saved when Medium or High has no open escalation (closed later)', () => {
      expect(savedToast(answer('MEDIUM', 5, null))).toBe('Vitals saved for Asha Bendre · early warning 5 (medium)');
    });

    it('never says the duty doctor was alerted', () => {
      expect(savedToast(answer('HIGH', 9, 15))).not.toContain('alerted');
    });
  });
});
