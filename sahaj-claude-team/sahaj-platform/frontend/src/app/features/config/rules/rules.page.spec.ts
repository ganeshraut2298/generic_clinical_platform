import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { Role } from '../../../core/platform/platform.model';
import { ShellStore } from '../../../core/shell/shell.store';
import { AlertRules } from './rules.model';
import { RulesPage } from './rules.page';

const URL = '/api/v1/opd/alert-rules';

const OPTIONS = {
  when: ['Token', 'Vitals'],
  cond: ['not done in 10 min', 'bill not paid'],
  then: ['Alert nurse', 'Alert owner'],
};

function data(overrides: Partial<AlertRules> = {}): AlertRules {
  return {
    rules: [
      { id: 'r1', when: 'Vitals', cond: 'not done in 10 min', then: 'Alert nurse', enabled: true },
      { id: 'r2', when: 'Token', cond: 'bill not paid', then: 'Alert owner', enabled: false },
    ],
    suggestions: [
      {
        index: 0,
        when: 'Billing',
        cond: 'unpaid at day closing',
        then: 'Alert owner',
        why: 'Every visit is settled',
      },
    ],
    options: OPTIONS,
    ...overrides,
  };
}

describe('RulesPage (alerts and escalation)', () => {
  let fixture: ComponentFixture<RulesPage>;
  let http: HttpTestingController;
  let snack: ReturnType<typeof vi.spyOn>;

  async function open(role: Role, initial: AlertRules | 'fail' = data()) {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        {
          provide: ShellStore,
          useValue: { me: () => ({ userId: 'u1', role, clinic: { name: 'Sai Clinic' } }) },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    snack = vi.spyOn(TestBed.inject(MatSnackBar), 'open');
    fixture = TestBed.createComponent(RulesPage);
    fixture.detectChanges();
    const req = http.expectOne(URL);
    if (initial === 'fail') {
      req.flush(
        { title: 'Server error', status: 500 },
        { status: 500, statusText: 'Server Error' },
      );
    } else {
      req.flush(initial);
    }
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const q = <T extends Element>(selector: string) => el().querySelector<T>(selector)!;

  afterEach(() => http.verify());

  it('shows both panels, the on count, the note and no "Ran N times"', async () => {
    await open('OWNER');
    const text = el().textContent!;
    expect(text).toContain('Your rules');
    expect(text).toContain('Suggested for Indian OPDs');
    expect(text).toContain('1 on');
    expect(text).toContain('Rules are saved now. Automatic checking starts in a later release.');
    expect(text).not.toContain('Ran ');
    expect(el().querySelectorAll('.rule').length).toBe(2);
    expect(el().querySelectorAll('select option').length).toBe(6);
    expect(text).toContain('If billing unpaid at day closing → alert owner');
  });

  it('shows the empty text when all suggestions are added', async () => {
    await open('OWNER', data({ suggestions: [] }));
    expect(el().textContent).toContain('All suggested rules are added.');
  });

  it('shows an error when loading fails', async () => {
    await open('OWNER', 'fail');
    expect(q('[role="alert"]')).toBeTruthy();
    expect(el().querySelector('.rule')).toBeNull();
  });

  it('is read-only for non-owners', async () => {
    await open('NURSE');
    expect(el().textContent).toContain('Only the owner can change the rules');
    expect(el().querySelector('button[role="switch"]')).toBeNull();
    expect(el().querySelector('button.xbtn')).toBeNull();
    expect(el().querySelector('button[type="submit"]')).toBeNull();
    expect(q<HTMLSelectElement>('#rule-when').disabled).toBe(true);
    expect(el().textContent).not.toContain('Add suggested rule');
  });

  it('adds a rule from the builder and toasts', async () => {
    await open('OWNER');
    const when = q<HTMLSelectElement>('#rule-when');
    when.value = 'Token';
    when.dispatchEvent(new Event('change'));
    q<HTMLButtonElement>('button[type="submit"]').click();
    const req = http.expectOne(URL);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      when: 'Token',
      cond: 'not done in 10 min',
      then: 'Alert nurse',
    });
    req.flush(
      data({
        rules: [
          {
            id: 'r3',
            when: 'Token',
            cond: 'not done in 10 min',
            then: 'Alert nurse',
            enabled: true,
          },
        ],
      }),
    );
    fixture.detectChanges();
    expect(snack).toHaveBeenCalledWith('Rule added and switched on', 'Close', expect.anything());
    expect(el().querySelectorAll('.rule').length).toBe(1);
  });

  it('says the rule already exists on 409', async () => {
    await open('OWNER');
    q<HTMLButtonElement>('button[type="submit"]').click();
    http
      .expectOne(URL)
      .flush(
        { title: 'Conflict', status: 409, detail: 'Duplicate' },
        { status: 409, statusText: 'Conflict' },
      );
    expect(snack).toHaveBeenCalledWith('That rule already exists.', 'Close', expect.anything());
  });

  it('adds a suggestion by its index', async () => {
    await open('OWNER');
    q<HTMLButtonElement>('.li button').click();
    const req = http.expectOne(`${URL}/suggestions/0`);
    expect(req.request.method).toBe('POST');
    req.flush(data({ suggestions: [] }));
    fixture.detectChanges();
    expect(snack).toHaveBeenCalledWith('Rule added and switched on', 'Close', expect.anything());
    expect(el().textContent).toContain('All suggested rules are added.');
  });

  it('switches a rule on or off', async () => {
    await open('OWNER');
    const switches = el().querySelectorAll<HTMLButtonElement>('button[role="switch"]');
    switches[1].click();
    const req = http.expectOne(`${URL}/r2`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ enabled: true });
    req.flush(
      data({
        rules: [
          {
            id: 'r1',
            when: 'Vitals',
            cond: 'not done in 10 min',
            then: 'Alert nurse',
            enabled: true,
          },
          { id: 'r2', when: 'Token', cond: 'bill not paid', then: 'Alert owner', enabled: true },
        ],
      }),
    );
    fixture.detectChanges();
    expect(el().textContent).toContain('2 on');
  });

  function confirmDelete(): void {
    const buttons = Array.from(el().querySelectorAll<HTMLButtonElement>('.confirm button'));
    buttons.find((b) => b.textContent!.trim() === 'Delete')!.click();
  }

  it('labels each switch and delete button with its rule, and asks before deleting', async () => {
    await open('OWNER');
    const sentence = 'If vitals not done in 10 min → alert nurse';
    expect(el().querySelector(`button[aria-label="Delete rule ${sentence}"]`)).toBeTruthy();
    expect(el().querySelector(`button[aria-label="Switch ${sentence} on or off"]`)).toBeTruthy();
    el().querySelectorAll<HTMLButtonElement>('button.xbtn')[0].click();
    fixture.detectChanges();
    http.expectNone(`${URL}/r1`);
    expect(el().querySelector('.confirm')).toBeTruthy();
  });

  it('deletes a rule', async () => {
    await open('OWNER');
    el().querySelectorAll<HTMLButtonElement>('button.xbtn')[0].click();
    fixture.detectChanges();
    confirmDelete();
    const req = http.expectOne(`${URL}/r1`);
    expect(req.request.method).toBe('DELETE');
    req.flush(data({ rules: [] }));
    fixture.detectChanges();
    expect(el().querySelectorAll('.rule').length).toBe(0);
    expect(el().textContent).toContain('0 on');
  });

  const saved = () =>
    data({
      rules: [
        {
          id: 's1',
          when: 'Vitals',
          cond: 'not done in 10 min',
          then: 'Alert nurse',
          enabled: true,
        },
        { id: 's2', when: 'Token', cond: 'bill not paid', then: 'Alert owner', enabled: false },
      ],
    });
  const unsaved = () =>
    data({
      rules: [
        {
          id: null,
          when: 'Vitals',
          cond: 'not done in 10 min',
          then: 'Alert nurse',
          enabled: true,
        },
        { id: null, when: 'Token', cond: 'bill not paid', then: 'Alert owner', enabled: false },
      ],
    });

  it('saves the starter rules first, then switches the matching rule', async () => {
    await open('OWNER', unsaved());
    el().querySelectorAll<HTMLButtonElement>('button[role="switch"]')[1].click();
    const save = http.expectOne(`${URL}/starter`);
    expect(save.request.method).toBe('POST');
    save.flush(saved());
    const put = http.expectOne(`${URL}/s2`);
    expect(put.request.method).toBe('PUT');
    expect(put.request.body).toEqual({ enabled: true });
    put.flush(saved());
  });

  it('saves the starter rules first, then deletes the matching rule', async () => {
    await open('OWNER', unsaved());
    el().querySelectorAll<HTMLButtonElement>('button.xbtn')[0].click();
    fixture.detectChanges();
    confirmDelete();
    http.expectOne(`${URL}/starter`).flush(saved());
    const del = http.expectOne(`${URL}/s1`);
    expect(del.request.method).toBe('DELETE');
    del.flush(data({ rules: [] }));
    fixture.detectChanges();
    expect(el().querySelectorAll('.rule').length).toBe(0);
  });
});
