import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Router, provideRouter } from '@angular/router';
import { problemDetailInterceptor } from '../../../../core/http/problem-detail.interceptor';
import { ShellStore } from '../../../../core/shell/shell.store';
import { departmentView, section, sections, summary } from '../../../emr/emr.fixtures';
import { EmrStarterDetail, EmrStarterSummary } from '../../../emr/emr.model';
import { AddDepartmentPage } from './add-department.page';

const EMR = '/api/v1/opd/emr';

const STARTERS: EmrStarterSummary[] = [
  {
    starterKey: 'physio', name: 'Physiotherapy', initials: 'PT', colour: '#6D3FC0',
    description: 'Built for session-based care: assessment on the first visit, then quick session notes against a package.',
    sectionCount: 24, specialtySectionCount: 10, offAtStart: ['Prescription (Rx)', 'Investigations', 'Systemic examination'],
  },
  {
    starterKey: 'dental', name: 'Dental', initials: 'DN', colour: '#0B6E69', description: null,
    sectionCount: 21, specialtySectionCount: 7, offAtStart: [],
  },
];

const PHYSIO: EmrStarterDetail = {
  ...STARTERS[0],
  layoutVersion: 1,
  sections: [
    ...sections().map((s) => ({ ...s, print: true })),
    section({ key: 'rom', name: 'Range of motion (goniometry)', kind: 'SPECIALTY', position: 7 }),
  ],
};

describe('AddDepartmentPage', () => {
  let fixture: ComponentFixture<AddDepartmentPage>;
  let http: HttpTestingController;
  let router: Router;
  let snack: ReturnType<typeof vi.fn>;
  let el: HTMLElement;

  async function boot(opts: { role?: string; saved?: boolean } = {}): Promise<void> {
    snack = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: ShellStore, useValue: { me: () => ({ role: opts.role ?? 'OWNER' }) } },
        { provide: MatSnackBar, useValue: { open: snack } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigate').mockResolvedValue(true);
    fixture = TestBed.createComponent(AddDepartmentPage);
    fixture.detectChanges();
    http.expectOne(`${EMR}/starter-departments`).flush(STARTERS);
    http.expectOne(`${EMR}/departments`).flush({
      canEdit: true,
      departments: opts.saved
        ? [summary(), summary({ departmentKey: 'dental', name: 'Dental', initials: 'DN', status: 'LIVE', publishedVersion: 2 })]
        : [summary()],
    });
    await settle();
    el = fixture.nativeElement as HTMLElement;
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const q = <T extends Element = HTMLElement>(sel: string) => el.querySelector<T>(sel);
  const qa = (sel: string) => Array.from(el.querySelectorAll<HTMLElement>(sel));
  const act = (name: string) => q<HTMLButtonElement>(`[data-act=${name}]`)!;

  async function type(selector: string, text: string): Promise<void> {
    const input = q<HTMLInputElement>(selector)!;
    input.value = text;
    input.dispatchEvent(new Event('input'));
    await settle();
  }

  async function pickPhysio(): Promise<void> {
    qa('.tcard')[0].click();
    http.expectOne(`${EMR}/starter-departments/physio`).flush(PHYSIO);
    await settle();
  }

  afterEach(() => http.verify());

  describe('step 1 (SAH-54-1)', () => {
    it('shows the title, the line, the stepper and the buttons', async () => {
      await boot();
      expect(q('h2')!.textContent).toContain('Add a department');
      expect(el.textContent).toContain('Start from a ready template built for Indian OPD practice, then change anything.');
      const steps = qa('.steps li:not(.sep)').map((s) => s.textContent!.replace(/\s+/g, ' ').trim());
      expect(steps).toEqual(['1Choose template', '2Review sections', '3Assign doctors & rooms']);
      expect(el.textContent).toContain('Cancel');
      expect(act('next').textContent).toContain('Next: review sections');
    });

    it('offers Start blank, Copy an existing department and the specialty templates with their counts', async () => {
      await boot();
      expect(el.textContent).toContain('Start blank');
      expect(el.textContent).toContain('Common sections only');
      expect(el.textContent).toContain('Copy an existing department');
      expect(el.textContent).toContain('e.g. copy Dental for a second dental chair');
      expect(el.textContent).toContain('Specialty templates');
      expect(qa('.tcard')[0].textContent).toContain('PT');
      expect(qa('.tcard')[0].textContent).toContain('Physiotherapy');
      expect(qa('.tcard')[0].textContent).toContain('10 specialty sections');
      expect(qa('.tcard')[1].textContent).toContain('7 specialty sections');
    });

    it('disables Copy with a reason when the clinic has no saved department', async () => {
      await boot();
      const copy = qa('.start')[1] as HTMLButtonElement;
      expect(copy.disabled).toBe(true);
      expect(q('#copy-why')!.textContent).toContain('after you have saved a draft');
    });

    it('lists only saved departments to copy', async () => {
      await boot({ saved: true });
      const copy = qa('.start')[1] as HTMLButtonElement;
      expect(copy.disabled).toBe(false);
      copy.click();
      await settle();
      expect(qa('.copylist .member')).toHaveLength(1);
      expect(qa('.copylist .member')[0].textContent).toContain('Dental');
    });

    it('shows the Template preview for the chosen template and the Turned off line', async () => {
      await boot();
      await pickPhysio();
      const right = q('.right')!.textContent!;
      expect(right).toContain('Template preview');
      expect(right).toContain('Physiotherapy');
      expect(right).toContain('Built for session-based care');
      expect(right).toContain('Range of motion (goniometry)');
      expect(right).toContain('Turned off by default: Prescription (Rx), Investigations and Systemic examination. You can switch them back on.');
    });

    it('offers the print template checkbox, ticked', async () => {
      await boot();
      const box = q<HTMLInputElement>('.chk input')!;
      expect(q('.chk')!.textContent).toContain('Also create a matching print template');
      expect(box.checked).toBe(true);
    });

    it('does not go on without a template and a name', async () => {
      await boot();
      expect(act('next').disabled).toBe(true);
      await pickPhysio();
      expect(act('next').disabled).toBe(true);
      await type('#dept-name', 'Physio annexe');
      expect(act('next').disabled).toBe(false);
    });

    it('tells a non-owner that only the owner can add a department', async () => {
      await boot({ role: 'DOCTOR' });
      expect(el.textContent).toContain('Only the owner can add a department.');
      expect(q('.tcard')).toBeNull();
    });
  });

  describe('steps 2 and 3', () => {
    it('shows the starter sections with the SAH-45 columns and goes back with the choice kept', async () => {
      await boot();
      await pickPhysio();
      await type('#dept-name', 'Physio annexe');
      act('next').click();
      await settle();
      expect(qa('.grp-h h3').map((h) => h.textContent!.trim())).toEqual([
        '1 · Assessment · Physio annexe',
        '2 · Treatment · Physio annexe',
      ]);
      act('back').click();
      await settle();
      expect(qa('.tcard')[0].getAttribute('aria-pressed')).toBe('true');
      expect(q<HTMLInputElement>('#dept-name')!.value).toBe('Physio annexe');
    });

    it('says what a blank department holds instead of a layout it cannot show', async () => {
      await boot();
      (qa('.start')[0] as HTMLButtonElement).click();
      await type('#dept-name', 'Blank one');
      act('next').click();
      await settle();
      expect(el.textContent).toContain('A blank department starts with the common sections only.');
    });

    it('lists the clinic doctors and takes room names', async () => {
      await boot();
      await pickPhysio();
      await type('#dept-name', 'Physio annexe');
      act('next').click();
      await settle();
      act('next').click();
      http.expectOne(`${EMR}/department-doctor-options`).flush([
        { membershipId: 'm1', name: 'Dr Asha Kulkarni', role: 'DOCTOR', degree: 'MBBS, MD' },
        { membershipId: 'm2', name: 'Dr Visiting', role: 'VISITING_DOCTOR', degree: null },
      ]);
      await settle();
      expect(qa('.doctors .member')).toHaveLength(2);
      expect(el.textContent).toContain('No doctor selected. You can add doctors later.');
      await type('input[placeholder="e.g. Room 2 or Chair 1"]', 'Chair 1');
      (qa('.roomadd button')[0] as HTMLButtonElement).click();
      await settle();
      expect(qa('.roomlist li')).toHaveLength(1);
      await type('input[placeholder="e.g. Room 2 or Chair 1"]', 'chair 1');
      (qa('.roomadd button')[0] as HTMLButtonElement).click();
      await settle();
      expect(el.textContent).toContain('That room is already in the list.');
    });
  });

  describe('create (SAH-54-4)', () => {
    async function toStep3(): Promise<void> {
      await pickPhysio();
      await type('#dept-name', 'Physio annexe');
      act('next').click();
      await settle();
      act('next').click();
      http.expectOne(`${EMR}/department-doctor-options`).flush([
        { membershipId: 'm1', name: 'Dr Asha Kulkarni', role: 'DOCTOR', degree: 'MBBS, MD' },
      ]);
      await settle();
    }

    it('creates the department from the choices and opens it in the configuration page', async () => {
      await boot();
      await toStep3();
      qa('.doctors .member')[0].click();
      await settle();
      act('create').click();
      const req = http.expectOne(`${EMR}/departments`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({
        name: 'Physio annexe',
        source: { kind: 'STARTER', starterKey: 'physio' },
        copyPrintFlags: true,
        doctorMembershipIds: ['m1'],
        rooms: [],
      });
      req.flush(departmentView({ departmentKey: 'd_abc1234567', name: 'Physio annexe', status: 'DRAFT' }), { status: 201, statusText: 'Created' });
      await settle();
      expect(router.navigate).toHaveBeenCalledWith(['/app/config/specialty'], {
        queryParams: { department: 'd_abc1234567' },
      });
    });

    it('sends the sections only when the owner changed them in step 2', async () => {
      await boot();
      await pickPhysio();
      await type('#dept-name', 'Physio annexe');
      act('next').click();
      await settle();
      const showRx = qa('.row').find((r) => r.querySelector('.nm')?.textContent === 'Prescription (Rx)')!
        .querySelector<HTMLButtonElement>('[role=switch]')!;
      showRx.click();
      await settle();
      act('next').click();
      http.expectOne(`${EMR}/department-doctor-options`).flush([]);
      await settle();
      expect(el.textContent).toContain('No doctors in the team yet');
      act('create').click();
      const req = http.expectOne(`${EMR}/departments`);
      expect(req.request.body.sections).toHaveLength(7);
      expect(req.request.body.sections.find((s: { key: string }) => s.key === 'rx').shown).toBe(false);
      req.flush(departmentView({ departmentKey: 'd_x' }), { status: 201, statusText: 'Created' });
    });

    it('asks for no print flags when the print template is not wanted', async () => {
      await boot();
      await pickPhysio();
      await type('#dept-name', 'Physio annexe');
      const box = q<HTMLInputElement>('.chk input')!;
      box.click();
      await settle();
      act('next').click();
      await settle();
      act('next').click();
      http.expectOne(`${EMR}/department-doctor-options`).flush([]);
      await settle();
      act('create').click();
      const req = http.expectOne(`${EMR}/departments`);
      expect(req.request.body.copyPrintFlags).toBe(false);
      expect(req.request.body.sections).toBeUndefined();
      req.flush(departmentView({ departmentKey: 'd_x' }), { status: 201, statusText: 'Created' });
    });

    it('shows the reason on the name when the name is taken, and keeps the choices', async () => {
      await boot();
      await toStep3();
      act('create').click();
      http.expectOne(`${EMR}/departments`).flush(
        { type: 'https://sahaj.in/problems/department-name-taken', title: 'Bad', status: 400, detail: 'A department with this name already exists.' },
        { status: 400, statusText: 'Bad' },
      );
      await settle();
      expect(q('.err-text')!.textContent).toContain('A department with this name already exists.');
      expect(qa('.tcard')[0].getAttribute('aria-pressed')).toBe('true');
      expect(q<HTMLInputElement>('#dept-name')!.value).toBe('Physio annexe');
    });

    it('keeps the wizard open with a message when the network fails', async () => {
      await boot();
      await toStep3();
      act('create').click();
      http.expectOne(`${EMR}/departments`).error(new ProgressEvent('error'));
      await settle();
      expect(q('[data-testid=wiz-error]')!.textContent).toContain('Your choices are kept');
      expect(act('create').disabled).toBe(false);
      expect(router.navigate).not.toHaveBeenCalled();
    });

    it('makes one request when Create is tapped twice quickly', async () => {
      await boot();
      await toStep3();
      act('create').click();
      act('create').click();
      const reqs = http.match(`${EMR}/departments`);
      expect(reqs).toHaveLength(1);
      reqs[0].flush(departmentView({ departmentKey: 'd_x' }), { status: 201, statusText: 'Created' });
    });
  });
});
