import {
  PIN_PILL,
  countdownText,
  digitsOnly,
  lockedNotice,
  notSetNotice,
  ownPinButton,
  resetToast,
  validateSetPin,
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
});
