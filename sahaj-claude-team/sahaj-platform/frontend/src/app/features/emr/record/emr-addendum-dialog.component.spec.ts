import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialogRef } from '@angular/material/dialog';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { casePaperContext, record } from '../emr.fixtures';
import { EmrAddendumDialogComponent } from './emr-addendum-dialog.component';
import { EmrRecordStore } from './emr-record.store';

const EMR = '/api/v1/opd/emr/records';
const ADDENDUM = { id: 'a1', reason: 'Wrong dose', text: 'The dose was 5 mg', createdAt: '2026-11-13T05:00:00Z', createdByName: 'Dr Test' };

describe('EmrAddendumDialogComponent (SAH-51-5)', () => {
  let fixture: ComponentFixture<EmrAddendumDialogComponent>;
  let http: HttpTestingController;
  let close: ReturnType<typeof vi.fn>;
  let el: HTMLElement;

  beforeEach(() => {
    close = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MatDialogRef, useValue: { close } },
        EmrRecordStore,
      ],
    });
    http = TestBed.inject(HttpTestingController);
    const store = TestBed.inject(EmrRecordStore);
    store.load('r1');
    http.expectOne(`${EMR}/r1`).flush(record({ status: 'SIGNED', readOnly: true, readOnlyReason: 'SIGNED' }));
    http.expectOne(`${EMR}/r1/case-paper`).flush(casePaperContext());
    fixture = TestBed.createComponent(EmrAddendumDialogComponent);
    el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  const type = (name: string, value: string) => {
    const f = el.querySelector<HTMLInputElement>(`[data-field=${name}]`)!;
    f.value = value;
    f.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  };
  const save = () => el.querySelector<HTMLButtonElement>('[data-act=addendum-save]')!.click();

  it('asks for a Reason and the Correction or addition, and says what is missing without calling the server', () => {
    expect(el.textContent).toContain('Reason');
    expect(el.textContent).toContain('Correction or addition');
    save();
    fixture.detectChanges();
    expect(el.textContent).toContain('Enter a reason');
    expect(el.textContent).toContain('Enter the correction or addition');
    http.expectNone(`${EMR}/r1/addenda`);
  });

  it('saves the trimmed reason and text with an Idempotency-Key and closes with the addendum', () => {
    type('reason', '  Wrong dose ');
    type('text', 'The dose was 5 mg\n');
    save();
    const req = http.expectOne(`${EMR}/r1/addenda`);
    expect(req.request.body).toEqual({ reason: 'Wrong dose', text: 'The dose was 5 mg' });
    expect(req.request.headers.get('Idempotency-Key')).toMatch(/^[A-Za-z0-9_-]{8,64}$/);
    req.flush(ADDENDUM, { status: 201, statusText: 'Created' });
    expect(close).toHaveBeenCalledWith(ADDENDUM);
  });

  it('keeps the key for a retry of the same text after a lost connection', () => {
    type('reason', 'Wrong dose');
    type('text', 'The dose was 5 mg');
    save();
    const first = http.expectOne(`${EMR}/r1/addenda`);
    const key = first.request.headers.get('Idempotency-Key');
    first.error(new ProgressEvent('error'));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid=addendum-error]')!.textContent).toContain('not saved');
    save();
    const second = http.expectOne(`${EMR}/r1/addenda`);
    expect(second.request.headers.get('Idempotency-Key')).toBe(key);
    second.flush(ADDENDUM);
  });

  it('shows the server\'s words when it refuses', () => {
    type('reason', 'Wrong dose');
    type('text', 'The dose was 5 mg');
    save();
    http.expectOne(`${EMR}/r1/addenda`).flush(
      { type: 'https://sahaj.in/problems/addendum-limit', title: 'x', status: 400, detail: 'Fifty addenda is the most for one visit.' },
      { status: 400, statusText: 'x' },
    );
    fixture.detectChanges();
    expect(el.querySelector('[data-testid=addendum-error]')!.textContent).toBe('Fifty addenda is the most for one visit.');
    expect(close).not.toHaveBeenCalled();
  });

  it('cancel saves nothing', () => {
    el.querySelector<HTMLButtonElement>('[data-act=addendum-cancel]')!.click();
    expect(close).toHaveBeenCalledWith(null);
  });
});
