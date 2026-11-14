import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { record } from '../emr.fixtures';
import { EmrRecordView } from '../emr.model';
import { specialtyRecord } from '../specialty/specialty.fixtures';
import { EmrHeaderComponent } from './emr-header.component';
import { EmrRecordStore } from './emr-record.store';

const EMR = '/api/v1/opd/emr/records';

describe('EmrHeaderComponent age (SAH-73-4)', () => {
  function textOf(view: EmrRecordView): string {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), EmrRecordStore] });
    const http = TestBed.inject(HttpTestingController);
    const store = TestBed.inject(EmrRecordStore);
    store.load('r1');
    http.expectOne(`${EMR}/r1`).flush(view);
    http.expectOne(`${EMR}/r1/case-paper`).flush({});
    const fixture = TestBed.createComponent(EmrHeaderComponent);
    fixture.detectChanges();
    return (fixture.nativeElement as HTMLElement).querySelector('.who')!.textContent!.replace(/\s+/g, ' ');
  }

  const withHeader = (view: EmrRecordView, over: Partial<EmrRecordView['header']>): EmrRecordView => ({
    ...view,
    header: { ...view.header, ...over },
  });

  it('shows the age in months for a Paediatrics record', () => {
    const view = withHeader(specialtyRecord('paeds', [], {}), {
      age: 1,
      ageDetail: { value: 18, unit: 'MONTHS', fromDateOfBirth: true },
      sex: 'F',
    });
    expect(textOf(view)).toContain('18 months · Female · UHID U-000042');
  });

  it('shows the whole-number age for a Paediatrics child with no date of birth', () => {
    const view = withHeader(specialtyRecord('paeds', [], {}), {
      age: 5,
      ageDetail: { value: 5, unit: 'YEARS', fromDateOfBirth: false },
    });
    expect(textOf(view)).toContain('5 yrs · Male');
  });

  it('keeps "52 y" in another department even when the server sends ageDetail', () => {
    const view = withHeader(record(), { ageDetail: { value: 52, unit: 'YEARS', fromDateOfBirth: false } });
    expect(textOf(view)).toContain('52 y · Male · UHID U-000042');
  });

  it('puts the vaccine chip of a Paediatrics child on the patient line', () => {
    const base = specialtyRecord('paeds', [], {});
    const view = { ...base, specialty: { ...base.specialty!, paeds: { ...base.specialty!.paeds!, dueTodayCount: 1, overdueCount: 2 } } } as EmrRecordView;
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), EmrRecordStore] });
    const http = TestBed.inject(HttpTestingController);
    TestBed.inject(EmrRecordStore).load('r1');
    http.expectOne(`${EMR}/r1`).flush(view);
    http.expectOne(`${EMR}/r1/case-paper`).flush({});
    const fixture = TestBed.createComponent(EmrHeaderComponent);
    fixture.detectChanges();
    const chip = (fixture.nativeElement as HTMLElement).querySelector('.who [data-testid=vaccine-chip]');
    expect(chip?.textContent?.trim()).toBe('3 vaccine(s) pending');
  });

  it('puts the payer chip of a Surgery visit on the patient line, amber while pre-authorisation is pending', () => {
    const base = specialtyRecord('surgery', [], {});
    const view = { ...base, specialty: { ...base.specialty!, surgery: { payerChip: 'Insurance (TPA) · pre-auth pending' } } } as unknown as EmrRecordView;
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), EmrRecordStore] });
    const http = TestBed.inject(HttpTestingController);
    TestBed.inject(EmrRecordStore).load('r1');
    http.expectOne(`${EMR}/r1`).flush(view);
    http.expectOne(`${EMR}/r1/case-paper`).flush({});
    const fixture = TestBed.createComponent(EmrHeaderComponent);
    fixture.detectChanges();
    const chip = (fixture.nativeElement as HTMLElement).querySelector('.who [data-testid=payer-chip]')!;
    expect(chip.textContent?.trim()).toBe('Insurance (TPA) · pre-auth pending');
    expect(chip.classList.contains('p-warn')).toBe(true);
  });

  describe('allergy tags (ADR-026 D12)', () => {
    function root(view: EmrRecordView): HTMLElement {
      TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), EmrRecordStore] });
      const http = TestBed.inject(HttpTestingController);
      TestBed.inject(EmrRecordStore).load('r1');
      http.expectOne(`${EMR}/r1`).flush(view);
      http.expectOne(`${EMR}/r1/case-paper`).flush({});
      const fixture = TestBed.createComponent(EmrHeaderComponent);
      fixture.detectChanges();
      return fixture.nativeElement as HTMLElement;
    }
    const none = { allergyText: null, allergyStatus: 'NONE_RECORDED' as const };

    it('Paediatrics draws a grey "No known allergy" chip for NONE_RECORDED and no red tag', () => {
      const el = root(withHeader(specialtyRecord('paeds', [], {}), none));
      const chip = el.querySelector('[data-testid=no-allergy-chip]')!;
      expect(chip.textContent?.trim()).toBe('No known allergy');
      expect(chip.classList.contains('p-wait')).toBe(true);
      expect(el.querySelector('.tags .p-crit')).toBeNull();
    });

    it('Paediatrics draws no chip for NOT_RECORDED or RECORDED, and the red tag only for RECORDED', () => {
      const notRecorded = root(withHeader(specialtyRecord('paeds', [], {}), { allergyText: null, allergyStatus: 'NOT_RECORDED' }));
      expect(notRecorded.querySelector('[data-testid=no-allergy-chip]')).toBeNull();
      expect(notRecorded.querySelector('.tags .p-crit')).toBeNull();
    });

    it('a recorded allergy shows the red tag and no grey chip', () => {
      const el = root(withHeader(specialtyRecord('paeds', [], {}), { allergyText: 'Penicillin', allergyStatus: 'RECORDED' }));
      expect(el.querySelector('.tags .p-crit')?.textContent?.trim()).toBe('Allergy: Penicillin');
      expect(el.querySelector('[data-testid=no-allergy-chip]')).toBeNull();
    });

    it('another department shows no grey chip even for NONE_RECORDED', () => {
      const el = root(withHeader(record(), none));
      expect(el.querySelector('[data-testid=no-allergy-chip]')).toBeNull();
      expect(el.querySelector('.tags .p-crit')).toBeNull();
    });
  });

  it('keeps the whole years of an older answer without ageDetail', () => {
    expect(textOf(record())).toContain('52 y · Male');
  });
});
