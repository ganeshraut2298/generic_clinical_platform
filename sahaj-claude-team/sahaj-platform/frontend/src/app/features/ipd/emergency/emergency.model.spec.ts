import { newClientKey as sharedClientKey } from '../../../shared/client-key';
import {
  EmergencyCase,
  EmergencyLevel,
  RegistrationDraft,
  ageSexText,
  forPatientText,
  isFullCase,
  staleQueueText,
  timeOfInstant,
  levelHint,
  levelTitle,
  newClientKey,
  parseAge,
  queueNote,
  referredToast,
  registeredToast,
  releasedToast,
  retriagedToast,
  seenToast,
  targetLabel,
  tileNumber,
  tileText,
  toCaseInput,
  validateRegistration,
  waitText,
} from './emergency.model';

const CASE: EmergencyCase = {
  id: 'c1',
  displayName: 'Asha Patil',
  age: 56,
  sex: 'F',
  complaint: 'Chest pain',
  identityPending: false,
  level: 1,
  levelName: 'Red',
  bay: 'ER-1',
  status: 'WAITING',
  arrivedAt: '2026-11-05T05:00:00Z',
  waitingMinutes: 38,
  targetMinutes: 30,
  overTarget: true,
  seenAt: null,
};

const DRAFT: RegistrationDraft = {
  unknown: false,
  name: 'Asha Patil',
  age: '56',
  sex: 'F',
  mobile: '9876543210',
  complaint: 'Chest pain',
  level: 2,
};

describe('emergency model', () => {
  it('writes the level hint with "within N min" or "now"', () => {
    const red: EmergencyLevel = { level: 1, name: 'Red', description: 'Immediate', tone: 'crit', targetMinutes: 5 };
    expect(levelHint(red)).toBe('Immediate · within 5 min');
    expect(levelHint({ ...red, name: 'Level 1', description: 'Resuscitation', targetMinutes: 0 })).toBe(
      'Resuscitation · now',
    );
  });

  it('writes the wait pill with a target in minutes or "target now"', () => {
    expect(waitText(CASE)).toBe('Waiting 38 min · target 30 min');
    expect(waitText({ waitingMinutes: 1, targetMinutes: 0 })).toBe('Waiting 1 min · target now');
  });

  it('writes the age and sex, the bay after them, and "?" for an unknown age', () => {
    expect(ageSexText(CASE)).toBe('56 F · ER-1');
    expect(ageSexText({ age: null, sex: 'M', bay: null })).toBe('? M');
  });

  it('writes the queue note and the tile', () => {
    expect(queueNote({ baysFree: 1, baysTotal: 2 })).toBe(
      'Most urgent first, then longest waiting · bays free 1 of 2',
    );
    expect(tileNumber({ writable: true, queue: 4, overTarget: 0 })).toBe('4');
    expect(tileNumber({ writable: false, queue: 0, overTarget: 0 })).toBe('—');
    expect(tileText({ writable: true, queue: 4, overTarget: 0 })).toEqual({
      text: 'All within target time',
      critical: false,
    });
    expect(tileText({ writable: true, queue: 4, overTarget: 2 })).toEqual({
      text: '2 over target time',
      critical: true,
    });
  });

  it('writes the toasts from the case in its new state', () => {
    expect(registeredToast({ ...CASE, displayName: 'Ravi Shah', levelName: 'Yellow', bay: null })).toBe(
      'Ravi Shah added to the emergency queue · Yellow',
    );
    expect(registeredToast(CASE)).toBe('Asha Patil added to the emergency queue · Red · bay ER-1');
    expect(seenToast(CASE)).toBe('Asha Patil seen by the duty doctor');
    expect(retriagedToast({ ...CASE, levelName: 'Level 2' })).toBe('Asha Patil re-triaged to Level 2 · logged');
    expect(releasedToast(CASE)).toBe('Asha Patil treated and released');
    expect(referredToast(CASE)).toBe('Asha Patil referred out');
  });

  it('writes the setup labels', () => {
    expect(targetLabel(0)).toBe('Now');
    expect(targetLabel(30)).toBe('30 min');
    expect(levelTitle({ name: 'Level 2', description: 'Emergent' })).toBe('Level 2 · Emergent');
  });

  describe('age', () => {
    it('is empty for no age, a whole number up to 120, otherwise invalid', () => {
      expect(parseAge('')).toBeNull();
      expect(parseAge('  ')).toBeNull();
      expect(parseAge('0')).toBe(0);
      expect(parseAge(' 120 ')).toBe(120);
      expect(parseAge('121')).toBeUndefined();
      expect(parseAge('4.5')).toBeUndefined();
      expect(parseAge('-1')).toBeUndefined();
      expect(parseAge('abc')).toBeUndefined();
    });
  });

  describe('registration validation', () => {
    it('accepts a complete draft', () => {
      expect(validateRegistration(DRAFT, true)).toEqual({});
    });

    it('asks for a name, or the box, and for the complaint', () => {
      const errors = validateRegistration({ ...DRAFT, name: '  ', complaint: ' ' }, true);
      expect(errors.name).toBe('Enter a name, or tick "Name not known yet"');
      expect(errors.complaint).toBe('Enter the main complaint');
    });

    it('needs no name when the box is ticked and unknown registration is on', () => {
      expect(validateRegistration({ ...DRAFT, unknown: true, name: '' }, true)).toEqual({});
      expect(validateRegistration({ ...DRAFT, unknown: true, name: '' }, false).name).toBeDefined();
    });

    it('limits the name to 80 and the complaint to 200 characters', () => {
      expect(validateRegistration({ ...DRAFT, name: 'a'.repeat(80) }, true).name).toBeUndefined();
      expect(validateRegistration({ ...DRAFT, name: 'a'.repeat(81) }, true).name).toBe(
        'Enter a name of up to 80 characters',
      );
      expect(validateRegistration({ ...DRAFT, complaint: 'c'.repeat(200) }, true).complaint).toBeUndefined();
      expect(validateRegistration({ ...DRAFT, complaint: 'c'.repeat(201) }, true).complaint).toBe(
        'Enter the main complaint in up to 200 characters',
      );
    });

    it('counts characters, not UTF-16 units', () => {
      expect(validateRegistration({ ...DRAFT, name: '😀'.repeat(80) }, true).name).toBeUndefined();
    });

    it('checks the age and the mobile', () => {
      expect(validateRegistration({ ...DRAFT, age: '121' }, true).age).toBe(
        'Enter the age as a whole number from 0 to 120, or leave it empty',
      );
      expect(validateRegistration({ ...DRAFT, age: '' }, true).age).toBeUndefined();
      expect(validateRegistration({ ...DRAFT, mobile: '12345' }, true).mobile).toBe(
        'Enter a 10-digit mobile number, or leave it empty',
      );
      expect(validateRegistration({ ...DRAFT, mobile: '5876543210' }, true).mobile).toBeDefined();
      expect(validateRegistration({ ...DRAFT, mobile: '' }, true).mobile).toBeUndefined();
    });
  });

  describe('request body', () => {
    it('trims text, sends empty mobile and age as null, and no name for an unknown patient', () => {
      expect(toCaseInput({ ...DRAFT, name: '  Asha  ', complaint: ' pain ', mobile: ' ', age: '' }, 'k1')).toEqual({
        clientKey: 'k1',
        identityUnknown: false,
        name: 'Asha',
        age: null,
        sex: 'F',
        mobile: null,
        complaint: 'pain',
        level: 2,
      });
      expect(toCaseInput({ ...DRAFT, unknown: true, name: 'ignored' }, 'k2')).toMatchObject({
        identityUnknown: true,
        name: null,
        age: 56,
        mobile: '9876543210',
      });
    });

    it('sends age and level as JSON numbers and identityUnknown as a boolean', () => {
      const body = JSON.parse(JSON.stringify(toCaseInput(DRAFT, 'k3'))) as Record<string, unknown>;
      expect(typeof body['age']).toBe('number');
      expect(typeof body['level']).toBe('number');
      expect(typeof body['identityUnknown']).toBe('boolean');
      const noAge = JSON.parse(JSON.stringify(toCaseInput({ ...DRAFT, age: '' }, 'k4'))) as Record<string, unknown>;
      expect(noAge['age']).toBeNull();
    });
  });

  describe('isFullCase', () => {
    it('is true for a full case and false for a reference, null and undefined', () => {
      expect(isFullCase(CASE)).toBe(true);
      expect(isFullCase({ id: 'c1', level: 2, status: 'RELEASED' })).toBe(false);
      expect(isFullCase(null)).toBe(false);
      expect(isFullCase(undefined)).toBe(false);
    });

    it('is true for a case with an empty display name (unknown patient)', () => {
      expect(isFullCase({ ...CASE, displayName: '' })).toBe(true);
    });
  });

  it('still exports newClientKey, which now lives in shared/client-key', () => {
    expect(newClientKey).toBe(sharedClientKey);
  });
});

describe('screen reader and stale-queue text', () => {
  it('forPatientText names the patient, or an unknown patient when the name is empty', () => {
    expect(forPatientText({ displayName: 'Asha Patil' })).toBe(' for Asha Patil');
    expect(forPatientText({ displayName: '   ' })).toBe(' for unknown patient');
  });

  it('timeOfInstant is the IST clock time in 12-hour style, empty for a bad value', () => {
    expect(timeOfInstant('2026-11-05T05:35:00Z')).toBe('11:05 AM');
    expect(timeOfInstant('2026-11-05T12:30:00Z')).toBe('6:00 PM');
    expect(timeOfInstant('2026-11-05T18:30:00Z')).toBe('12:00 AM');
    expect(timeOfInstant('')).toBe('');
    expect(timeOfInstant(null)).toBe('');
    expect(timeOfInstant('not a date')).toBe('');
  });

  it('timeOfInstant writes a plain space before AM/PM when the browser gives U+202F (G-15)', () => {
    let fake = '';
    const spy = vi.spyOn(Intl, 'DateTimeFormat').mockImplementation(function () {
      return { format: () => fake };
    } as never);
    try {
      fake = '11:05\u202Fam';
      expect(timeOfInstant('2026-11-05T05:35:00Z')).toBe('11:05 AM');
      expect(staleQueueText('2026-11-05T05:35:00Z')).toBe('Could not refresh the queue. Last updated 11:05 AM IST');
    } finally {
      spy.mockRestore();
    }
  });

  it('staleQueueText gives the warning with the last good time, or without it', () => {
    expect(staleQueueText('2026-11-05T05:35:00Z')).toBe('Could not refresh the queue. Last updated 11:05 AM IST');
    expect(staleQueueText(undefined)).toBe('Could not refresh the queue.');
  });
});
