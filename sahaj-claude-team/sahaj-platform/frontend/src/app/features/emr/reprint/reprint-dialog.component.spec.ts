import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { casePaperContext, field, section } from '../emr.fixtures';
import { EmrReprint } from '../emr-finish.model';
import { ReprintDialogComponent } from './reprint-dialog.component';

const base = (over: Partial<EmrReprint> = {}): EmrReprint => ({
  visitId: 'v1',
  recordId: 'r1',
  reprint: true,
  signedAt: '2026-11-13T05:30:00Z',
  context: casePaperContext(),
  sections: [{ key: 'advice', name: 'Advice', heading: 'ADVICE', values: { note: 'Rest and fluids' } }],
  addenda: [],
  layout: {
    departmentKey: 'gm',
    departmentName: 'General Medicine',
    starterKey: 'gm',
    layoutSource: 'PUBLISHED',
    versionNo: 1,
    choiceSource: 'DOCTOR_SPECIALTY',
    takenAt: '2026-11-12T05:00:00Z',
    sections: [section({ key: 'advice', name: 'Advice', print: true, fields: [field({ key: 'note' })] })],
  },
  specialty: null,
  specialtyReference: null,
  ...over,
});

const PAEDS: Partial<EmrReprint> = {
  specialty: {
    profile: 'paeds',
    mode: null,
    tagNote: '',
    paeds: {
      dateOfBirth: '2025-05-10',
      ageMonths: 18,
      ageDays: 2,
      growthNotes: [],
      vaccines: [{ vaccineKey: 'bcg', state: 'GIVEN', givenOn: '2025-05-10' }],
      dueTodayCount: 0,
      overdueCount: 0,
      nextDue: null,
      milestonesSetUp: true,
      doseWeightKg: null,
      doseWeightChangedRowIds: [],
    },
  },
  specialtyReference: { profile: 'paeds', tagNote: '', lists: { iapGroups: [{ key: 'birth', label: 'Birth' }], iapVaccines: [{ key: 'bcg', label: 'BCG', group: 'birth' }] } },
};

describe('ReprintDialogComponent (SAH-51-6, SAH-62-4)', () => {
  function open(reprint: EmrReprint): ComponentFixture<ReprintDialogComponent> {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: MatDialogRef, useValue: { close: vi.fn() } },
        { provide: MAT_DIALOG_DATA, useValue: { reprint } },
      ],
    });
    const fixture = TestBed.createComponent(ReprintDialogComponent);
    fixture.detectChanges();
    return fixture;
  }

  it('offers "Print vaccination card" on a Paediatrics reprint and prints the card from the signed answer', () => {
    const el = open(base(PAEDS)).nativeElement as HTMLElement;
    const printed: string[] = [];
    const print = vi.spyOn(window, 'print').mockImplementation(() => {
      printed.push(document.querySelector('.print-root')?.textContent ?? '');
    });
    el.querySelector<HTMLButtonElement>('[data-act=reprint-card]')!.click();
    expect(print).toHaveBeenCalledTimes(1);
    expect(printed[0]).toContain('Vaccination card');
    expect(printed[0]).toContain('BCG — given 10 May 2025');
    print.mockRestore();
  });

  it('says so when the card could not print', () => {
    const fixture = open(base(PAEDS));
    const print = vi.spyOn(window, 'print').mockImplementation(() => {
      throw new Error('blocked');
    });
    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('[data-act=reprint-card]')!.click();
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('[data-testid=reprint-card-message]')?.textContent).toContain('We could not print. Try again.');
    print.mockRestore();
  });

  it('shows no card button for another department', () => {
    const el = open(base()).nativeElement as HTMLElement;
    expect(el.querySelector('[data-act=reprint-card]')).toBeNull();
    expect(el.querySelector('[data-act=reprint-print]')).not.toBeNull();
  });
});
