import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { problem } from '../emr/emr.fixtures';
import { verifiedResult } from './pharmacist.fixtures';
import { QuerySheetComponent, QuerySheetData } from './query-sheet.component';

const QUERY = '/api/v1/ipd/medicine-orders/o1/query';

describe('QuerySheetComponent', () => {
  let fixture: ComponentFixture<QuerySheetComponent>;
  let http: HttpTestingController;
  let close: ReturnType<typeof vi.fn>;
  let ref: { close: ReturnType<typeof vi.fn>; disableClose?: boolean };
  let onConflict: ReturnType<typeof vi.fn<() => void>>;

  async function open(): Promise<void> {
    close = vi.fn();
    ref = { close };
    onConflict = vi.fn<() => void>();
    const data: QuerySheetData = {
      orderId: 'o1',
      orderVersion: 2,
      drugName: 'Tab. Paracetamol 650 mg',
      subtitle: 'Asha Patil · ordered by Dr. Meera Kulkarni',
      onConflict,
    };
    await TestBed.configureTestingModule({
      imports: [QuerySheetComponent],
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: ref },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(QuerySheetComponent);
    fixture.detectChanges();
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const squash = (node: Element | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const q = <T extends HTMLElement>(sel: string) => el().querySelector<T>(sel);
  const ask = async (text: string): Promise<void> => {
    const box = q<HTMLTextAreaElement>('#sf-qd')!;
    box.value = text;
    box.dispatchEvent(new Event('input'));
    q<HTMLButtonElement>('[data-act=qd-ok]')!.click();
    await settle();
  };

  afterEach(() => http.verify());

  it('shows the title, the subtitle and the question box with a 300 character limit', async () => {
    await open();
    expect(squash(q('h2'))).toBe('Query doctor · Tab. Paracetamol 650 mg');
    expect(squash(q('.sub'))).toBe('Asha Patil · ordered by Dr. Meera Kulkarni');
    expect(squash(q('label[for=sf-qd]'))).toBe('Your question for the doctor');
    expect(q<HTMLTextAreaElement>('#sf-qd')!.maxLength).toBe(300);
  });

  it('sends nothing for an empty question and says so', async () => {
    await open();
    await ask('   ');
    http.expectNone(QUERY);
    expect(squash(q('#sf-qd-err'))).toBe('Write your question for the doctor.');
  });

  it('sends the question, the version and a client key, then closes with the answer', async () => {
    await open();
    await ask(' Is this the right dose? ');
    const req = http.expectOne(QUERY);
    expect(req.request.body).toEqual({
      clientKey: expect.stringMatching(/^[0-9a-f-]{36}$/),
      orderVersion: 2,
      question: 'Is this the right dose?',
    });
    expect(q<HTMLButtonElement>('[data-act=qd-ok]')!.disabled).toBe(true);
    expect(ref.disableClose).toBe(true);
    const answer = verifiedResult({ outcome: 'QUERIED' });
    req.flush(answer);
    await settle();
    expect(close).toHaveBeenCalledWith(answer);
  });

  it('shows the field message of a 400 under the box', async () => {
    await open();
    await ask('x');
    http.expectOne(QUERY).flush(
      problem('validation', 400, 'Keep the question to 300 characters.', [
        { field: 'question', message: 'Keep the question to 300 characters.' },
      ]),
      { status: 400, statusText: 'Bad Request' },
    );
    await settle();
    expect(squash(q('#sf-qd-err'))).toBe('Keep the question to 300 characters.');
    expect(close).not.toHaveBeenCalled();
  });

  it('keeps the sheet open on a 409, reloads the panel behind it and switches Send question off', async () => {
    await open();
    await ask('Is this right?');
    http.expectOne(QUERY).flush(problem('order-state', 409, 'This order is not waiting for the pharmacist.'), {
      status: 409,
      statusText: 'Conflict',
    });
    await settle();
    expect(squash(q('[data-testid=query-failure]'))).toBe('This order is not waiting for the pharmacist.');
    expect(onConflict).toHaveBeenCalledTimes(1);
    expect(q<HTMLButtonElement>('[data-act=qd-ok]')!.disabled).toBe(true);
    expect(close).not.toHaveBeenCalled();
  });

  it.each([
    'The doctor who ordered this cannot check it. Ask the pharmacist.',
    'The doctor who changed this order cannot check it. Ask the pharmacist.',
  ])('403 self-verification shows "%s" and switches Send question off', async (detail) => {
    await open();
    await ask('Is this right?');
    http.expectOne(QUERY).flush(problem('self-verification', 403, detail), { status: 403, statusText: 'Forbidden' });
    await settle();
    expect(squash(q('[data-testid=query-failure]'))).toBe(detail);
    expect(q<HTMLButtonElement>('[data-act=qd-ok]')!.disabled).toBe(true);
    expect(onConflict).not.toHaveBeenCalled();
    expect(close).not.toHaveBeenCalled();
  });

  it('keeps what was typed and shows a generic text when the connection is lost', async () => {
    await open();
    await ask('Is this right?');
    http.expectOne(QUERY).error(new ProgressEvent('error'));
    await settle();
    expect(squash(q('[data-testid=query-failure]'))).toBe('We could not send that question. Try again.');
    expect(q<HTMLTextAreaElement>('#sf-qd')!.value).toBe('Is this right?');
    expect(q<HTMLButtonElement>('[data-act=qd-ok]')!.disabled).toBe(false);
  });

  it('Cancel closes without an answer', async () => {
    await open();
    q<HTMLButtonElement>('[data-act=qd-cancel]')!.click();
    expect(close).toHaveBeenCalledWith();
  });
});
