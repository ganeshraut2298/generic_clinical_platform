import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { problemDetailInterceptor } from '../../../../core/http/problem-detail.interceptor';
import { CancerJourney } from '../specialty.model';
import { JOURNEY_CHANGED, OncJourneyDialogComponent } from './onc-journey-dialog.component';

const PUT = '/api/v1/opd/emr/records/r1/cancer-journey';
const current: CancerJourney = {
  version: 2,
  stageGroup: 'IIA',
  tnmText: 'cT2 cN1 M0',
  markersText: null,
  ki67Pct: 20,
  geneticsText: null,
  intent: 'CURATIVE',
  setting: null,
  lineNo: null,
  boardDate: null,
  recordedAt: '2026-07-26T10:00:00Z',
  recordedByName: 'Dr Test',
};

describe('Cancer journey edit form (SAH-75-7; ours)', () => {
  let fixture: ComponentFixture<OncJourneyDialogComponent>;
  let http: HttpTestingController;
  let el: HTMLElement;
  let close: ReturnType<typeof vi.fn>;

  function boot(data: CancerJourney | null): void {
    close = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: { recordId: 'r1', current: data } },
        { provide: MatDialogRef, useValue: { close } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(OncJourneyDialogComponent);
    fixture.detectChanges();
    el = fixture.nativeElement as HTMLElement;
  }

  const field = (name: string) => el.querySelector<HTMLInputElement & HTMLSelectElement>(`[data-field=${name}]`)!;
  function set(name: string, value: string, event = 'input'): void {
    const f = field(name);
    f.value = value;
    f.dispatchEvent(new Event(event));
    fixture.detectChanges();
  }
  const submit = () => {
    el.querySelector<HTMLButtonElement>('[data-act=save-journey]')!.click();
    fixture.detectChanges();
  };

  afterEach(() => http.verify());

  it('starts from the version the doctor sees', () => {
    boot(current);
    expect(field('stageGroup').value).toBe('IIA');
    expect(field('ki67Pct').value).toBe('20');
    expect(field('intent').value).toBe('CURATIVE');
  });

  it('refuses a Ki-67 above 100 with a message and sends nothing', () => {
    boot(null);
    set('ki67Pct', '101');
    submit();
    expect(el.querySelector('[data-testid=ki67-error]')!.textContent).toBe('Enter a whole number from 0 to 100.');
    http.expectNone(PUT);
  });

  it('refuses a tumour board date more than a year ahead', () => {
    boot(null);
    set('boardDate', '2099-01-01');
    submit();
    expect(el.querySelector('[data-testid=board-error]')).not.toBeNull();
    http.expectNone(PUT);
  });

  it('saves with the version read (0 when there is none) and closes with true', () => {
    boot(null);
    set('stageGroup', 'IIIA', 'change');
    set('lineNo', '1', 'change');
    submit();
    const req = http.expectOne(PUT);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toMatchObject({ expectedVersion: 0, stageGroup: 'IIIA', lineNo: 1, tnmText: null });
    req.flush({ ...current, version: 1 });
    expect(close).toHaveBeenCalledWith(true);
  });

  it('shows a stale save as "The journey was changed. Reload." and reloads the strip on close', () => {
    boot(current);
    submit();
    http.expectOne(PUT).flush({ type: 'https://sahaj.in/problems/journey-changed', title: 'x', status: 409 }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(el.querySelector('[data-testid=journey-error]')!.textContent).toBe(JOURNEY_CHANGED);
    el.querySelector<HTMLButtonElement>('[data-act=cancel]')!.click();
    expect(close).toHaveBeenCalledWith(true);
  });

  it('keeps the form and says the server\'s words when the save is refused', () => {
    boot(current);
    submit();
    http.expectOne(PUT).flush({ type: 'https://sahaj.in/problems/emr-record-signed', title: 'x', status: 409, detail: 'This visit is signed.' }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(el.querySelector('[data-testid=journey-error]')!.textContent).toBe('This visit is signed.');
    expect(close).not.toHaveBeenCalled();
  });
});
