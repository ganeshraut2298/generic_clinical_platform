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

  it('keeps the whole years of an older answer without ageDetail', () => {
    expect(textOf(record())).toContain('52 y · Male');
  });
});
