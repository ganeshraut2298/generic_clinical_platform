import {
  CONFIRM_MISSING,
  PATIENT_MISSING,
  REASON_MISSING,
  grantToast,
  markReviewedName,
  patientOption,
  reviewHeadline,
  reviewSmallLine,
  validateDraft,
} from './emergency-access.model';

describe('emergency-access.model (SAH-34)', () => {
  it('writes each option of the select Patient as "<name> · bed <code> · <UHID>" (UHID in place of MRN)', () => {
    expect(patientOption({ admissionId: 'a', patientName: 'Asha Patil', bedCode: '101-A', uhid: 'PT-000042' })).toBe(
      'Asha Patil · bed 101-A · PT-000042',
    );
    expect(patientOption({ admissionId: 'a', patientName: 'Asha Patil', bedCode: '101-A', uhid: null })).toBe(
      'Asha Patil · bed 101-A · —',
    );
  });

  it('has the toast of DEC-031: the owner will see it on the Safety board, never "has been told"', () => {
    const text = grantToast('Asha Patil');
    expect(text).toBe('Emergency access to Asha Patil for 30 minutes. The owner will see this on the Safety board.');
    expect(text).not.toContain('been told');
  });

  describe('validateDraft (AC 47)', () => {
    const ok = { admissionId: 'a1', reasonKey: 'DETERIORATING', confirmed: true };

    it('passes a patient, a known reason and the ticked box', () => {
      expect(validateDraft(ok)).toEqual({});
    });

    it('asks for a reason, with the fixed text', () => {
      expect(validateDraft({ ...ok, reasonKey: '' })).toEqual({ reasonKey: REASON_MISSING });
      expect(REASON_MISSING).toBe('Choose a reason for emergency access.');
      expect(validateDraft({ ...ok, reasonKey: 'OTHER' })).toEqual({ reasonKey: REASON_MISSING });
    });

    it('asks for the tick, with the fixed text', () => {
      expect(validateDraft({ ...ok, confirmed: false })).toEqual({ confirmed: CONFIRM_MISSING });
      expect(CONFIRM_MISSING).toBe('Tick the box to confirm this access is recorded.');
    });

    it('asks for a patient and reports every error together', () => {
      expect(validateDraft({ admissionId: '', reasonKey: '', confirmed: false })).toEqual({
        admissionId: PATIENT_MISSING,
        reasonKey: REASON_MISSING,
        confirmed: CONFIRM_MISSING,
      });
    });
  });

  describe('the owner review row (AC 66)', () => {
    const row = {
      staffName: 'Meera Joshi',
      patientName: 'Asha Patil',
      bedCode: '101-A',
      reasonLabel: 'Patient deteriorating, rapid response',
      openedAt: '2026-10-24T05:35:00Z', // 11:05 AM IST
    };

    it('reads "<staff> opened <patient> · bed <code>"', () => {
      expect(reviewHeadline(row)).toBe('Meera Joshi opened Asha Patil · bed 101-A');
    });

    it('shows the time as "11:05 AM" today and "24 Oct, 11:05 AM" on an earlier day', () => {
      expect(reviewSmallLine(row, '2026-10-24T07:30:00Z')).toBe(
        'Patient deteriorating, rapid response · 11:05 AM · outside their wards',
      );
      expect(reviewSmallLine(row, '2026-10-25T07:30:00Z')).toBe(
        'Patient deteriorating, rapid response · 24 Oct, 11:05 AM · outside their wards',
      );
    });

    it('names the button for a screen reader', () => {
      expect(markReviewedName(row)).toBe('Mark reviewed: Meera Joshi opened Asha Patil');
    });
  });
});
