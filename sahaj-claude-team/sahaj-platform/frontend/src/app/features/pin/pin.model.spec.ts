import {
  PIN_PILL,
  countdownText,
  digitsOnly,
  lockedNotice,
  notSetNotice,
  ownPinButton,
  resetToast,
  validateSetPin,
  colleagueEntryText,
  colleagueTriesRowText,
  colleagueTriesSheetTitle,
  colleagueTriesText,
  istClockText,
  noticeStateLine,
} from './pin.model';

describe('pin.model', () => {
  it('labels the pills and the own-row button', () => {
    expect(PIN_PILL.SET.text).toBe('PIN set');
    expect(PIN_PILL.LOCKED.text).toBe('PIN locked');
    expect(PIN_PILL.NOT_SET.text).toBe('No PIN yet');
    expect(ownPinButton('NOT_SET')).toBe('Set my PIN');
    expect(ownPinButton('SET')).toBe('Change my PIN');
    expect(ownPinButton('LOCKED')).toBe('Change my PIN');
    expect(resetToast('Rahul Deshmukh')).toBe('PIN reset for Rahul Deshmukh.');
  });

  it('words the no-PIN notice by reason', () => {
    expect(notSetNotice(null)).toBe('You have not set your PIN yet. Set it to verify orders.');
    expect(notSetNotice('RESET_BY_OWNER')).toBe('The clinic owner reset your PIN. Set a new one to verify orders.');
    expect(notSetNotice('TOO_MANY_LOCKS')).toBe(
      'Your PIN was switched off after too many wrong tries. Set a new one to verify orders.',
    );
  });

  it('words the lock notice with the time', () => {
    expect(lockedNotice('11:20 AM')).toBe('Your PIN is locked after 5 wrong tries. Try again at 11:20 AM.');
    expect(lockedNotice('')).toBe('Your PIN is locked after 5 wrong tries. Try again later.');
  });

  it('formats the resend countdown', () => {
    expect(countdownText(28)).toBe('0:28');
    expect(countdownText(65)).toBe('1:05');
    expect(countdownText(-3)).toBe('0:00');
  });

  it('keeps digits only, at most the limit', () => {
    expect(digitsOnly('48a21', 4)).toBe('4821');
    expect(digitsOnly('123456789', 6)).toBe('123456');
    expect(digitsOnly('abc', 4)).toBe('');
  });

  describe('validateSetPin', () => {
    const ok = { code: '493017', newPin: '4821', confirmPin: '4821' };
    it('accepts a 6-digit code, a 4-digit PIN and a matching confirmation', () => {
      expect(validateSetPin(ok)).toEqual({});
    });
    it('refuses with the contract texts, every field together', () => {
      expect(validateSetPin({ code: '49', newPin: '48', confirmPin: '12' })).toEqual({
        code: 'Enter the 6-digit code.',
        newPin: 'Enter a 4-digit PIN.',
        confirmPin: 'The two PINs do not match.',
      });
    });
    it('refuses a confirmation that differs, and letters', () => {
      expect(validateSetPin({ ...ok, confirmPin: '4822' })).toEqual({ confirmPin: 'The two PINs do not match.' });
      expect(validateSetPin({ ...ok, newPin: '48a1', confirmPin: '48a1' })).toEqual({ newPin: 'Enter a 4-digit PIN.' });
    });
  });

  describe('colleague tries texts (SAH-38, NEW)', () => {
    const notice = { unseenCount: 3, latestAt: '2026-11-07T05:35:12.123456Z', state: 'SET', lockedUntil: null, notSetReason: null } as const;

    it('T2: plural and singular, counts only', () => {
      expect(colleagueTriesText(3)).toBe('3 wrong tries of your PIN by colleagues since your last use.');
      expect(colleagueTriesText(1)).toBe('1 wrong try of your PIN by a colleague since your last use.');
    });

    it('T3: the lock line with the IST time, the switched-off line, and nothing else', () => {
      expect(noticeStateLine({ ...notice, state: 'LOCKED', lockedUntil: '2026-11-07T05:35:00Z' })).toBe(
        'Your PIN is locked until 11:05 AM IST.',
      );
      expect(noticeStateLine({ ...notice, state: 'LOCKED', lockedUntil: null })).toBe('Your PIN is locked.');
      expect(noticeStateLine({ ...notice, state: 'NOT_SET', notSetReason: 'TOO_MANY_LOCKS' })).toBe(
        'Your PIN was switched off. Set a new one.',
      );
      expect(noticeStateLine({ ...notice, state: 'NOT_SET', notSetReason: 'RESET_BY_OWNER' })).toBeNull();
      expect(noticeStateLine(notice)).toBeNull();
    });

    it('formats an IST clock and refuses a bad value', () => {
      expect(istClockText('2026-11-07T09:30:00Z')).toBe('3:00 PM');
      expect(istClockText('nonsense')).toBe('');
      expect(istClockText(null)).toBe('');
    });

    it('T4 and T4b: the owner row line', () => {
      expect(colleagueTriesRowText(4)).toBe('4 wrong tries by colleagues in 7 days');
      expect(colleagueTriesRowText(1)).toBe('1 wrong try by a colleague in 7 days');
    });

    it('T5, T10, T11: history lines and the title', () => {
      const e = { at: '2026-11-07T05:35:00Z', byMembershipId: 'm1', byName: 'Sunita Pawar', count: 2 };
      expect(colleagueEntryText({ ...e, kind: 'WRONG_TRIES' })).toBe('Wrong PIN tries by Sunita Pawar: 2');
      expect(colleagueEntryText({ ...e, kind: 'RIGHT_AFTER_WRONG', count: null })).toBe(
        'Right PIN entered by Sunita Pawar after wrong tries',
      );
      expect(colleagueEntryText({ ...e, kind: 'LOCKED', byMembershipId: null, byName: null, count: null })).toBe(
        'PIN locked after wrong tries by colleagues',
      );
      expect(colleagueEntryText({ ...e, kind: 'WRONG_TRIES', byName: null })).toBe(
        'Wrong PIN tries by Former staff member: 2',
      );
      expect(colleagueTriesSheetTitle('Meera Patil')).toBe('Wrong PIN tries · Meera Patil');
    });
  });
});
