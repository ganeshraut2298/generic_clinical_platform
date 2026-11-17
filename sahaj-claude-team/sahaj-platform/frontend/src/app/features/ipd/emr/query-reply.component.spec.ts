import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { ScopeRefusals } from '../ipd-scope-refusal';
import { ORDER, problem, sheetOf } from './emr.fixtures';
import { MedicineOrderRow, OrderActionResult } from './emr.model';
import { QueryReplyComponent, REPLY_MAX, REPLY_MISSING } from './query-reply.component';

const URL = '/api/v1/ipd/medicine-orders/o1/query-reply';
const QUERIED: MedicineOrderRow = { ...ORDER, status: 'QUERIED', orderVersion: 2 };

describe('QueryReplyComponent (SAH-76-2, OQ-76-11)', () => {
  let fixture: ComponentFixture<QueryReplyComponent>;
  let http: HttpTestingController;
  let answers: OrderActionResult[];
  let report: ReturnType<typeof vi.spyOn>;

  async function open(): Promise<void> {
    answers = [];
    await TestBed.configureTestingModule({
      imports: [QueryReplyComponent],
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    report = vi.spyOn(TestBed.inject(ScopeRefusals), 'report');
    fixture = TestBed.createComponent(QueryReplyComponent);
    fixture.componentRef.setInput('order', QUERIED);
    fixture.componentRef.setInput('admissionId', 'a1');
    fixture.componentInstance.answered.subscribe((a) => answers.push(a));
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const box = () => el().querySelector<HTMLTextAreaElement>('#reply-o1')!;
  const send = () => el().querySelector<HTMLButtonElement>('[data-act=query-reply]')!;
  const squash = (node: Element | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const error = () => el().querySelector('#reply-err-o1');

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function reply(text: string): Promise<void> {
    box().value = text;
    box().dispatchEvent(new Event('input'));
    send().click();
    await settle();
  }

  afterEach(() => http.verify());

  it('has the Reply label, a 300 character limit and a button named for the medicine', async () => {
    await open();
    expect(squash(el().querySelector('label[for=reply-o1]'))).toBe('Reply');
    expect(box().maxLength).toBe(REPLY_MAX);
    expect(REPLY_MAX).toBe(300);
    expect(send().getAttribute('aria-label')).toBe('Send reply for Tab. Paracetamol 650 mg');
  });

  it.each(['', '   ', '\n'])('sends nothing for the reply "%s" and says "Write your reply." as an alert', async (text) => {
    await open();
    await reply(text);
    http.expectNone(URL);
    expect(squash(error())).toBe(REPLY_MISSING);
    expect(REPLY_MISSING).toBe('Write your reply.');
    expect(error()!.getAttribute('role')).toBe('alert');
    expect(box().getAttribute('aria-invalid')).toBe('true');
    expect(box().getAttribute('aria-describedby')).toBe('reply-err-o1');
  });

  it('clears the message as soon as the nurse types again', async () => {
    await open();
    await reply('');
    box().value = 'Y';
    box().dispatchEvent(new Event('input'));
    await settle();
    expect(error()).toBeNull();
    expect(box().getAttribute('aria-invalid')).toBeNull();
  });

  it('sends the version of the question, the trimmed reply and a key, then passes the sheet and the toast on', async () => {
    await open();
    await reply('  Checked with the chart.  ');
    const req = http.expectOne(URL);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      clientKey: expect.stringMatching(/^[0-9a-f-]{36}$/),
      orderVersion: 2,
      reply: 'Checked with the chart.',
    });
    expect(send().disabled).toBe(true);
    expect(send().getAttribute('aria-busy')).toBe('true');
    const sheet = sheetOf();
    req.flush(sheet);
    await settle();
    expect(answers).toEqual([{ kind: 'done', sheet, toast: 'Reply sent for Tab. Paracetamol 650 mg.' }]);
  });

  it('a second tap while the answer is awaited sends nothing more', async () => {
    await open();
    await reply('Checked.');
    send().click();
    send().click();
    http.expectOne(URL).flush(sheetOf());
  });

  it('a 409 order-changed passes the server text on so the sheet is read again', async () => {
    await open();
    await reply('Checked.');
    http
      .expectOne(URL)
      .flush(problem('order-changed', 409, 'This order was changed by someone else. Reload and check the new order.'), {
        status: 409,
        statusText: 'x',
      });
    await settle();
    expect(answers).toEqual([
      { kind: 'conflict', message: 'This order was changed by someone else. Reload and check the new order.' },
    ]);
  });

  it('a 400 on the reply shows the field text under the box and keeps what was typed', async () => {
    await open();
    await reply('Checked.');
    http.expectOne(URL).flush(
      problem('validation', 400, 'x', [{ field: 'reason', message: 'Keep the reply to 300 characters.' }]),
      { status: 400, statusText: 'x' },
    );
    await settle();
    expect(squash(error())).toBe('Keep the reply to 300 characters.');
    expect(box().value).toBe('Checked.');
    expect(answers).toEqual([]);
  });

  it('a lost connection shows the plain text, keeps the reply and allows another try', async () => {
    await open();
    await reply('Checked.');
    http.expectOne(URL).error(new ProgressEvent('error'));
    await settle();
    expect(squash(error())).toBe('We could not send that reply. Try again.');
    expect(box().value).toBe('Checked.');
    expect(send().disabled).toBe(false);
    expect(answers).toEqual([]);
  });

  it('takes a new key after 409 client-key-reused and keeps the same key for a plain retry', async () => {
    await open();
    await reply('Checked.');
    const first = http.expectOne(URL);
    first.error(new ProgressEvent('error'));
    await settle();
    send().click();
    await settle();
    const retry = http.expectOne(URL);
    expect(retry.request.body.clientKey).toBe(first.request.body.clientKey);
    retry.flush(problem('client-key-reused', 409, 'Reload the page and try again.'), { status: 409, statusText: 'x' });
    await settle();
    send().click();
    await settle();
    const fresh = http.expectOne(URL);
    expect(fresh.request.body.clientKey).not.toBe(first.request.body.clientKey);
    fresh.flush(sheetOf());
  });

  it('403 outside-scope reports the refusal to the screen and shows nothing else', async () => {
    await open();
    await reply('Checked.');
    http.expectOne(URL).flush(problem('outside-scope', 403, 'This patient is not in your wards.'), {
      status: 403,
      statusText: 'x',
    });
    await settle();
    expect(report).toHaveBeenCalledWith('a1', true);
    expect(answers).toEqual([]);
    expect(error()).toBeNull();
  });
});
