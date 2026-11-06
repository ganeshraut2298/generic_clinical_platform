import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { EmergencyRegisterComponent } from './emergency-register.component';
import { EmergencyCaseInput, EmergencyQueue } from './emergency.model';

const URL = '/api/v1/ipd/emergency/cases';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

const LEVELS3: EmergencyQueue['levels'] = [
  { level: 1, name: 'Red', description: 'Immediate', tone: 'crit', targetMinutes: 5 },
  { level: 2, name: 'Yellow', description: 'Urgent', tone: 'warn', targetMinutes: 30 },
  { level: 3, name: 'Green', description: 'Can wait', tone: 'good', targetMinutes: 120 },
];
const LEVELS5: EmergencyQueue['levels'] = [
  { level: 1, name: 'Level 1', description: 'Resuscitation', tone: 'crit', targetMinutes: 0 },
  { level: 2, name: 'Level 2', description: 'Emergent', tone: 'crit', targetMinutes: 10 },
  { level: 3, name: 'Level 3', description: 'Urgent', tone: 'warn', targetMinutes: 30 },
  { level: 4, name: 'Level 4', description: 'Less urgent', tone: 'good', targetMinutes: 60 },
  { level: 5, name: 'Level 5', description: 'Non-urgent', tone: 'good', targetMinutes: 120 },
];

const queueOf = (over: Partial<EmergencyQueue> = {}): EmergencyQueue => ({
  state: 'on',
  scale: 3,
  levels: LEVELS3,
  defaultLevel: 2,
  unknownPatientRegistration: true,
  baysTotal: 2,
  baysFree: 2,
  counts: { writable: true, queue: 0, overTarget: 0 },
  cases: [],
  canRegister: true,
  canRetriage: true,
  canAct: true,
  canAdmit: true,
  asOf: '2026-11-05T05:02:00Z',
  ...over,
});

describe('EmergencyRegisterComponent', () => {
  let fixture: ComponentFixture<EmergencyRegisterComponent>;
  let http: HttpTestingController;
  let registered: EmergencyQueue[];
  let conflicts: number;

  async function open(queue: EmergencyQueue = queueOf()) {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(EmergencyRegisterComponent);
    fixture.componentRef.setInput('queue', queue);
    registered = [];
    conflicts = 0;
    fixture.componentInstance.registered.subscribe((q) => registered.push(q));
    fixture.componentInstance.conflict.subscribe(() => conflicts++);
    await settle();
  }

  async function settle() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const text = () => (el().textContent ?? '').replace(/\s+/g, ' ');
  const field = (id: string) => el().querySelector<HTMLInputElement>(`#${id}`)!;
  const type = (id: string, value: string) => {
    field(id).value = value;
    field(id).dispatchEvent(new Event('input'));
  };
  const submitBtn = () => el().querySelector<HTMLButtonElement>('button[type=submit]')!;
  const button = (label: string) =>
    Array.from(el().querySelectorAll<HTMLButtonElement>('button')).find((b) => b.textContent!.trim() === label)!;
  const levelBtn = (level: number) => el().querySelector<HTMLButtonElement>(`.triseg [data-level="${level}"]`)!;

  async function submit() {
    el().querySelector('form')!.dispatchEvent(new Event('submit'));
    await settle();
  }

  async function fill(over: Partial<Record<'name' | 'age' | 'mobile' | 'complaint', string>> = {}) {
    type('er-name', over.name ?? 'Ravi Shah');
    type('er-age', over.age ?? '40');
    type('er-mobile', over.mobile ?? '9876543210');
    type('er-complaint', over.complaint ?? 'Chest pain for 1 hour');
    await settle();
  }

  const created = (req: TestRequest, queue: EmergencyQueue = queueOf(), status = 201) =>
    req.flush(queue, { status, statusText: status === 201 ? 'Created' : 'OK' });

  afterEach(() => http.verify());

  describe('form', () => {
    it('shows the panel title, the small text, the fields in order and the button', async () => {
      await open();
      expect(el().querySelector('h2')!.textContent).toBe('Quick registration');
      expect(text()).toContain('Name, age, sex and the complaint are enough. Full details can wait.');
      const labels = Array.from(el().querySelectorAll('mat-label, .field-label')).map((l) =>
        l.textContent!.replace(/\s+/g, ' ').trim(),
      );
      expect(labels).toEqual([
        'Name',
        'Age (approx.)',
        'Sex',
        'Mobile of patient or relative (optional)',
        'Main complaint',
        'Triage level',
      ]);
      expect(text()).toContain('Name not known yet');
      expect(field('er-name').getAttribute('placeholder')).toBe('Full name');
      expect(field('er-complaint').getAttribute('placeholder')).toBe('For example: chest pain for 1 hour');
      expect(submitBtn().textContent!.trim()).toBe('Register and add to queue');
      expect(submitBtn().querySelector('app-icon')).not.toBeNull();
    });

    it('defaults to Male and to the second level, with the description and target on each level', async () => {
      await open();
      expect(button('Male').getAttribute('aria-pressed')).toBe('true');
      expect(button('Female').getAttribute('aria-pressed')).toBe('false');
      expect(button('Other').getAttribute('aria-pressed')).toBe('false');
      expect(levelBtn(2).getAttribute('aria-pressed')).toBe('true');
      expect(levelBtn(1).getAttribute('aria-pressed')).toBe('false');
      const hints = Array.from(el().querySelectorAll('.triseg button')).map(
        (b) => `${b.querySelector('b')!.textContent} / ${b.querySelector('small')!.textContent}`,
      );
      expect(hints).toEqual([
        'Red / Immediate · within 5 min',
        'Yellow / Urgent · within 30 min',
        'Green / Can wait · within 120 min',
      ]);
      expect(levelBtn(1).classList).toContain('tri-crit');
      expect(levelBtn(2).classList).toContain('tri-warn');
      expect(levelBtn(3).classList).toContain('tri-good');
    });

    it('shows five levels, with "now" for a target of 0', async () => {
      await open(queueOf({ scale: 5, levels: LEVELS5 }));
      expect(el().querySelectorAll('.triseg button').length).toBe(5);
      expect(levelBtn(1).querySelector('b')!.textContent).toBe('Level 1');
      expect(levelBtn(1).querySelector('small')!.textContent).toBe('Resuscitation · now');
      expect(levelBtn(2).getAttribute('aria-pressed')).toBe('true');
    });

    it('hides the checkbox when unknown patient registration is off', async () => {
      await open(queueOf({ unknownPatientRegistration: false }));
      expect(text()).not.toContain('Name not known yet');
      expect(field('er-name')).not.toBeNull();
    });

    it('hides the Name field while "Name not known yet" is ticked', async () => {
      await open();
      el().querySelector<HTMLInputElement>('input[type=checkbox]')!.click();
      await settle();
      expect(el().querySelector('#er-name')).toBeNull();
      el().querySelector<HTMLInputElement>('input[type=checkbox]')!.click();
      await settle();
      expect(el().querySelector('#er-name')).not.toBeNull();
    });

    it('changes sex and level with the buttons', async () => {
      await open();
      button('Female').click();
      levelBtn(1).click();
      await settle();
      expect(button('Female').getAttribute('aria-pressed')).toBe('true');
      expect(levelBtn(1).getAttribute('aria-pressed')).toBe('true');
      expect(levelBtn(2).getAttribute('aria-pressed')).toBe('false');
    });
  });

  describe('messages', () => {
    it('asks for a name or the box, and for the complaint, and sends nothing', async () => {
      await open();
      await submit();
      expect(text()).toContain('Enter a name, or tick "Name not known yet"');
      expect(text()).toContain('Enter the main complaint');
      expect(field('er-name').getAttribute('aria-invalid')).toBe('true');
      expect(document.activeElement).toBe(field('er-name'));
      http.expectNone(URL);
    });

    it('needs only the complaint when the box is ticked', async () => {
      await open();
      el().querySelector<HTMLInputElement>('input[type=checkbox]')!.click();
      await settle();
      await submit();
      expect(text()).not.toContain('Enter a name');
      expect(text()).toContain('Enter the main complaint');
    });

    it('checks the lengths, the age and the mobile', async () => {
      await open();
      await fill({ name: 'a'.repeat(81), age: '121', mobile: '12345', complaint: 'c'.repeat(201) });
      await submit();
      expect(text()).toContain('Enter a name of up to 80 characters');
      expect(text()).toContain('Enter the age as a whole number from 0 to 120, or leave it empty');
      expect(text()).toContain('Enter a 10-digit mobile number, or leave it empty');
      expect(text()).toContain('Enter the main complaint in up to 200 characters');
      http.expectNone(URL);
    });

    it('lets age and mobile stay empty', async () => {
      await open();
      await fill({ age: '', mobile: '' });
      await submit();
      const req = http.expectOne(URL);
      expect(req.request.body).toMatchObject({ age: null, mobile: null });
      created(req);
    });
  });

  describe('registering', () => {
    it('sends the contract body with a client key and the default level', async () => {
      await open();
      await fill();
      await submit();
      const req = http.expectOne(URL);
      expect(req.request.method).toBe('POST');
      const body = req.request.body as EmergencyCaseInput;
      expect(body).toEqual({
        clientKey: expect.stringMatching(UUID),
        identityUnknown: false,
        name: 'Ravi Shah',
        age: 40,
        sex: 'M',
        mobile: '9876543210',
        complaint: 'Chest pain for 1 hour',
        level: 2,
      });
      created(req);
    });

    it('sends an unknown patient without a name', async () => {
      await open();
      el().querySelector<HTMLInputElement>('input[type=checkbox]')!.click();
      type('er-complaint', 'Unconscious');
      button('Other').click();
      await settle();
      await submit();
      const req = http.expectOne(URL);
      expect(req.request.body).toMatchObject({ identityUnknown: true, name: null, sex: 'O', age: null });
      created(req);
    });

    it('emits the new queue, clears the form and puts the level back to the second level', async () => {
      await open();
      await fill();
      levelBtn(1).click();
      button('Female').click();
      await settle();
      await submit();
      const queue = queueOf({ counts: { writable: true, queue: 1, overTarget: 0 } });
      created(http.expectOne(URL), queue);
      await settle();
      expect(registered).toEqual([queue]);
      expect(field('er-name').value).toBe('');
      expect(field('er-age').value).toBe('');
      expect(field('er-mobile').value).toBe('');
      expect(field('er-complaint').value).toBe('');
      expect(button('Male').getAttribute('aria-pressed')).toBe('true');
      expect(levelBtn(2).getAttribute('aria-pressed')).toBe('true');
    });

    it('takes a 200 repeat of a client key like a new registration', async () => {
      await open();
      await fill();
      await submit();
      created(http.expectOne(URL), queueOf(), 200);
      await settle();
      expect(registered.length).toBe(1);
    });

    it('clears the form and makes a new key when the 200 repeat answers with a reference', async () => {
      await open();
      await fill();
      levelBtn(1).click();
      await settle();
      await submit();
      const first = http.expectOne(URL);
      const key = (first.request.body as EmergencyCaseInput).clientKey;
      const queue = queueOf({ actedCase: { id: 'c1', level: 1, status: 'RELEASED' } });
      created(first, queue, 200);
      await settle();
      expect(registered).toEqual([queue]);
      expect(field('er-name').value).toBe('');
      expect(field('er-complaint').value).toBe('');
      expect(levelBtn(2).getAttribute('aria-pressed')).toBe('true');
      expect(el().querySelector('.notice.err')).toBeNull();
      await fill();
      await submit();
      const second = http.expectOne(URL);
      expect((second.request.body as EmergencyCaseInput).clientKey).not.toBe(key);
      created(second);
    });

    it('sends age and level as numbers and identityUnknown as a boolean in the JSON', async () => {
      await open();
      await fill();
      levelBtn(3).click();
      await settle();
      await submit();
      const req = http.expectOne(URL);
      const sent = JSON.parse(JSON.stringify(req.request.body)) as Record<string, unknown>;
      expect(sent['age']).toBe(40);
      expect(sent['level']).toBe(3);
      expect(sent['identityUnknown']).toBe(false);
      created(req);
    });

    it('switches the button off while the request runs, so a double tap sends one request', async () => {
      await open();
      await fill();
      await submit();
      expect(submitBtn().disabled).toBe(true);
      await submit();
      await submit();
      const req = http.expectOne(URL);
      created(req);
      await settle();
      expect(submitBtn().disabled).toBe(false);
    });
  });

  describe('retry with the same key (AC13)', () => {
    it('keeps what was typed after a failure and sends the same client key again', async () => {
      await open();
      await fill();
      await submit();
      const first = http.expectOne(URL);
      const key = (first.request.body as EmergencyCaseInput).clientKey;
      first.error(new ProgressEvent('error'));
      await settle();
      expect(text()).toContain('We could not add this patient to the queue. What you typed is kept. Try again.');
      expect(field('er-name').value).toBe('Ravi Shah');
      expect(field('er-complaint').value).toBe('Chest pain for 1 hour');
      expect(submitBtn().disabled).toBe(false);
      expect(registered).toEqual([]);

      await submit();
      const retry = http.expectOne(URL);
      expect((retry.request.body as EmergencyCaseInput).clientKey).toBe(key);
      created(retry, queueOf(), 200);
    });

    it('makes a new key after any edit of a field', async () => {
      await open();
      await fill();
      await submit();
      const first = http.expectOne(URL);
      const key = (first.request.body as EmergencyCaseInput).clientKey;
      first.error(new ProgressEvent('error'));
      await settle();

      type('er-age', '41');
      await settle();
      await submit();
      const second = http.expectOne(URL);
      expect((second.request.body as EmergencyCaseInput).clientKey).not.toBe(key);
      expect((second.request.body as EmergencyCaseInput).age).toBe(41);
      created(second);
    });

    it('makes a new key after a success', async () => {
      await open();
      await fill();
      await submit();
      const first = http.expectOne(URL);
      const key = (first.request.body as EmergencyCaseInput).clientKey;
      created(first);
      await settle();
      await fill();
      await submit();
      const second = http.expectOne(URL);
      expect((second.request.body as EmergencyCaseInput).clientKey).not.toBe(key);
      created(second);
    });

    it('also treats a changed sex or level as an edit', async () => {
      await open();
      await fill();
      await submit();
      const first = http.expectOne(URL);
      const key = (first.request.body as EmergencyCaseInput).clientKey;
      first.error(new ProgressEvent('error'));
      await settle();
      levelBtn(1).click();
      await settle();
      await submit();
      const second = http.expectOne(URL);
      expect((second.request.body as EmergencyCaseInput).clientKey).not.toBe(key);
      expect((second.request.body as EmergencyCaseInput).level).toBe(1);
      created(second);
    });
  });

  describe('server answers', () => {
    const problem = (slug: string, status: number, detail: string, fieldErrors?: unknown[]) => ({
      type: `https://sahaj.in/problems/${slug}`,
      title: slug,
      status,
      detail,
      ...(fieldErrors ? { fieldErrors } : {}),
    });

    it('shows field errors of a 400 under their fields and keeps the data', async () => {
      await open();
      await fill();
      await submit();
      http.expectOne(URL).flush(
        problem('validation', 400, 'Check the fields', [
          { field: 'mobile', message: 'Enter a 10-digit mobile number, or leave it empty' },
          { field: 'level', message: 'Choose a triage level' },
        ]),
        { status: 400, statusText: 'Bad Request' },
      );
      await settle();
      expect(el().querySelector('#er-mobile-err')!.textContent).toBe(
        'Enter a 10-digit mobile number, or leave it empty',
      );
      expect(el().querySelector('.notice.err')!.textContent).toContain('Choose a triage level');
      expect(field('er-name').value).toBe('Ravi Shah');
    });

    it('shows an identityUnknown 400 as a general notice, keeps the values and the key', async () => {
      await open();
      await fill();
      await submit();
      const first = http.expectOne(URL);
      const key = (first.request.body as EmergencyCaseInput).clientKey;
      first.flush(
        problem('validation', 400, 'Check the fields', [
          { field: 'identityUnknown', message: 'Reload the page and try again.' },
        ]),
        { status: 400, statusText: 'Bad Request' },
      );
      await settle();
      expect(el().querySelector('.notice.err')!.textContent).toContain('Reload the page and try again.');
      expect(el().querySelectorAll('.err-text').length).toBe(0);
      expect(field('er-name').value).toBe('Ravi Shah');
      expect(field('er-age').value).toBe('40');
      expect(field('er-complaint').value).toBe('Chest pain for 1 hour');
      expect(conflicts).toBe(0);
      await submit();
      const retry = http.expectOne(URL);
      expect((retry.request.body as EmergencyCaseInput).clientKey).toBe(key);
      created(retry);
    });

    it('shows the 409 message and asks the page to reload', async () => {
      await open();
      await fill();
      await submit();
      http.expectOne(URL).flush(problem('ipd-readonly', 409, 'IPD is read-only. Nothing new can be added.'), {
        status: 409,
        statusText: 'Conflict',
      });
      await settle();
      expect(el().querySelector('.notice.err')!.textContent).toContain('IPD is read-only. Nothing new can be added.');
      expect(conflicts).toBe(1);
    });

    it('shows the 403 message', async () => {
      await open();
      await fill();
      await submit();
      http.expectOne(URL).flush(problem('forbidden', 403, 'Your role cannot do this.'), {
        status: 403,
        statusText: 'Forbidden',
      });
      await settle();
      expect(el().querySelector('.notice.err')!.textContent).toContain('Your role cannot do this.');
      expect(conflicts).toBe(0);
    });
  });
});
