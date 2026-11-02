import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  FormArray,
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Router } from '@angular/router';
import { debounceTime, distinctUntilChanged } from 'rxjs';
import { ApiError } from '../../core/http/problem-detail';
import { MOBILE_PATTERN } from '../../core/auth/auth.model';
import { AuthService } from '../../core/auth/auth.service';
import { applyBrand } from '../../core/shell/apply-brand';
import { BRAND_SWATCHES, Brand, Language, ROLE_LABELS, Role } from '../../core/platform/platform.model';
import { IconComponent } from '../../shared/icon/icon.component';
import { SetupApi } from './setup.api';
import {
  FeeTiming,
  FollowUpFee,
  SetupAnswers,
  SetupLimits,
  SpecialtyCode,
  STAFF_ROLES,
} from './setup.model';
import { slugify } from './slugify';

const STEPS: readonly { title: string; description: string }[] = [
  { title: 'Your clinic', description: 'Name and address' },
  { title: 'Specialty', description: 'Ready template' },
  { title: 'Doctors and staff', description: 'Logins and roles' },
  { title: 'How your OPD runs', description: 'Fee, queue, follow-up' },
  { title: 'Look and web address', description: 'Colour and clinic link' },
];

const STEP_HEADS: readonly { title: string; subtitle: string }[] = [
  { title: 'Tell us about your clinic', subtitle: 'This prints on every case paper and bill.' },
  {
    title: 'What kind of clinic is it?',
    subtitle: 'We load a ready workflow, case paper, fee list and medicine favourites for your specialty.',
  },
  {
    title: 'Who works in your clinic?',
    subtitle: "Each person gets their own login and sees only their own tasks.",
  },
  {
    title: 'How does your OPD run today?',
    subtitle: 'Answer four questions and we set up the workflow to match. You can change it any time.',
  },
  {
    title: 'Choose your colour and web address',
    subtitle: 'Your team opens the clinic at this address from any phone, tablet or computer.',
  },
];

const SPECIALTIES: readonly { code: SpecialtyCode; name: string; summary: string; loads: string[] }[] = [
  {
    code: 'gp',
    name: 'General practice',
    summary: 'Adults and children, common illnesses',
    loads: ['General OPD case paper', 'Common medicine favourites', 'Standard fee list'],
  },
  {
    code: 'dental',
    name: 'Dental',
    summary: 'Procedures, tooth chart',
    loads: ['Tooth chart case paper', 'Dental procedure fee list', 'Dental medicine favourites'],
  },
  {
    code: 'paeds',
    name: 'Paediatrics',
    summary: 'Growth chart, vaccination',
    loads: ['Growth chart case paper', 'Vaccination schedule', 'Paediatric dosing favourites'],
  },
  {
    code: 'homeo',
    name: 'Homeopathy',
    summary: 'Repertory, case taking',
    loads: ['Homeopathy case paper', 'Repertory-style history', 'Homeopathy medicine favourites'],
  },
];

type DoctorGroup = FormGroup<{
  name: FormControl<string>;
  specialty: FormControl<string>;
  feeRupees: FormControl<number>;
  mobile: FormControl<string>;
}>;

type StaffGroup = FormGroup<{
  name: FormControl<string>;
  role: FormControl<Role>;
  mobile: FormControl<string>;
}>;

function doctorGroup(): DoctorGroup {
  return new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    specialty: new FormControl('', { nonNullable: true }),
    feeRupees: new FormControl(400, { nonNullable: true, validators: [Validators.min(0)] }),
    mobile: new FormControl('', { nonNullable: true, validators: [Validators.pattern(MOBILE_PATTERN)] }),
  });
}

function staffGroup(): StaffGroup {
  return new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    role: new FormControl<Role>('FRONT_DESK', { nonNullable: true }),
    mobile: new FormControl('', { nonNullable: true, validators: [Validators.pattern(MOBILE_PATTERN)] }),
  });
}

/**
 * The five-step clinic setup wizard (SAH-4), mockup `pSetup`/`suClinic`/`suSpec`/`suTeam`/
 * `suOpd`/`suLook`. Resumes from the server, saves on Continue, debounces the slug check and
 * shows the plan-limit message the backend returns.
 */
@Component({
  selector: 'app-setup-wizard',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    IconComponent,
  ],
  styleUrl: './setup-wizard.page.scss',
  templateUrl: './setup-wizard.page.html',
})
export class SetupWizardPage implements OnInit {
  private readonly api = inject(SetupApi);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly steps = STEPS;
  protected readonly stepHeads = STEP_HEADS;
  protected readonly specialties = SPECIALTIES;
  protected readonly brandSwatches = BRAND_SWATCHES;
  protected readonly staffRoles = STAFF_ROLES;
  protected readonly roleLabels = ROLE_LABELS;

  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly completing = signal(false);
  protected readonly currentStep = signal(1);
  protected readonly limits = signal<SetupLimits>({ doctors: 2, users: 5 });
  protected readonly slugChecking = signal(false);
  protected readonly slugAvailable = signal<boolean | null>(null);
  protected readonly slugAlternatives = signal<string[]>([]);
  protected readonly loadError = signal<string | null>(null);

  protected readonly stepHead = computed(() => this.stepHeads[this.currentStep() - 1]);
  protected readonly minutesLeft = computed(() => (6 - this.currentStep()) * 3);
  protected readonly progressPercent = computed(() => ((this.currentStep() - 1) / 5) * 100);
  protected readonly totalUsers = computed(
    () => this.doctors.length + this.staff.length,
  );
  protected readonly atUserLimit = computed(() => this.totalUsers() >= this.limits().users);
  protected readonly atDoctorLimit = computed(
    () => this.doctors.length >= Math.min(2, this.limits().doctors),
  );

  protected readonly clinicForm = new FormGroup({
    name: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(160)] }),
    address: new FormControl('', { nonNullable: true }),
    city: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    pin: new FormControl('', { nonNullable: true, validators: [Validators.pattern(/^[0-9]{6}$/)] }),
    phone: new FormControl('', { nonNullable: true }),
    registrationNo: new FormControl('', { nonNullable: true }),
  });

  protected readonly specialtyForm = new FormGroup({
    code: new FormControl<SpecialtyCode>('gp', { nonNullable: true, validators: [Validators.required] }),
  });

  protected readonly teamForm = new FormGroup({
    doctors: new FormArray<DoctorGroup>([doctorGroup()]),
    staff: new FormArray<StaffGroup>([]),
  });

  protected readonly opdForm = new FormGroup({
    feeTiming: new FormControl<FeeTiming>('before', { nonNullable: true, validators: [Validators.required] }),
    queue: new FormControl(true, { nonNullable: true }),
    vitals: new FormControl(true, { nonNullable: true }),
    followUpDays: new FormControl(7, { nonNullable: true, validators: [Validators.min(1), Validators.max(30)] }),
    followUpFee: new FormControl<FollowUpFee>('free', { nonNullable: true, validators: [Validators.required] }),
  });

  protected readonly lookForm = new FormGroup({
    brand: new FormControl<Brand>('blue', { nonNullable: true, validators: [Validators.required] }),
    slug: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.pattern(/^[a-z0-9][a-z0-9-]{2,23}$/)],
    }),
    language: new FormControl<Language>('en', { nonNullable: true, validators: [Validators.required] }),
  });

  private slugEdited = false;

  protected get doctors(): FormArray<DoctorGroup> {
    return this.teamForm.controls.doctors;
  }

  protected get staff(): FormArray<StaffGroup> {
    return this.teamForm.controls.staff;
  }

  constructor() {
    this.clinicForm.controls.name.valueChanges
      .pipe(takeUntilDestroyed())
      .subscribe((name) => {
        if (!this.slugEdited) {
          this.lookForm.controls.slug.setValue(slugify(name), { emitEvent: false });
        }
      });

    this.lookForm.controls.slug.valueChanges
      .pipe(debounceTime(400), distinctUntilChanged(), takeUntilDestroyed())
      .subscribe((slug) => this.checkSlug(slug, true));
  }

  ngOnInit(): void {
    this.api.getState().subscribe({
      next: (state) => {
        this.currentStep.set(state.currentStep);
        this.limits.set(state.limits);
        this.applyAnswers(state.answers as SetupAnswers);
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        const problem = error instanceof ApiError ? error.problem : null;
        if (problem?.status === 403) {
          void this.router.navigate(['/app']);
          return;
        }
        this.loadError.set('We could not load your setup progress. Try again.');
      },
    });
  }

  protected addDoctor(): void {
    if (this.atDoctorLimit() || this.atUserLimit()) {
      return;
    }
    this.doctors.push(doctorGroup());
  }

  protected removeDoctor(index: number): void {
    if (index === 0) {
      return;
    }
    this.doctors.removeAt(index);
  }

  protected addStaff(): void {
    if (this.atUserLimit()) {
      return;
    }
    this.staff.push(staffGroup());
  }

  protected removeStaff(index: number): void {
    this.staff.removeAt(index);
  }

  protected selectSpecialty(code: SpecialtyCode): void {
    this.specialtyForm.controls.code.setValue(code);
  }

  protected specialtyByCode(code: SpecialtyCode) {
    return this.specialties.find((item) => item.code === code) ?? this.specialties[0];
  }

  protected selectBrand(brand: Brand): void {
    this.lookForm.controls.brand.setValue(brand);
    applyBrand(brand);
  }

  protected onSlugInput(value: string): void {
    this.slugEdited = true;
    this.lookForm.controls.slug.setValue(value.toLowerCase().trim());
  }

  protected useAlternative(slug: string): void {
    this.slugEdited = true;
    this.lookForm.controls.slug.setValue(slug);
  }

  protected adjustFollowUpDays(delta: number): void {
    const next = Math.min(30, Math.max(1, this.opdForm.controls.followUpDays.value + delta));
    this.opdForm.controls.followUpDays.setValue(next);
  }

  protected back(): void {
    if (this.currentStep() > 1) {
      this.currentStep.update((step) => step - 1);
    }
  }

  protected continue(): void {
    if (this.saving() || this.completing()) {
      return;
    }
    const step = this.currentStep();
    if (!this.validateStep(step)) {
      return;
    }
    this.saving.set(true);
    this.api.saveStep(step, this.bodyForStep(step)).subscribe({
      next: (state) => {
        this.saving.set(false);
        this.limits.set(state.limits);
        if (step < 5) {
          this.currentStep.set(step + 1);
        } else {
          this.completeSetup();
        }
      },
      error: (error: unknown) => {
        this.saving.set(false);
        this.handleStepError(error);
      },
    });
  }

  protected exitSetup(): void {
    this.auth.logout().subscribe(() => void this.router.navigate(['/login']));
  }

  private completeSetup(): void {
    this.completing.set(true);
    this.api.complete().subscribe({
      next: (result) => {
        applyBrand(result.brand);
        setTimeout(() => void this.router.navigate(['/app']), 1200);
      },
      error: (error: unknown) => {
        this.completing.set(false);
        this.handleStepError(error);
      },
    });
  }

  private validateStep(step: number): boolean {
    if (step === 1) {
      if (!this.clinicForm.controls.name.value.trim()) {
        this.clinicForm.controls.name.markAsTouched();
        this.toast('Enter your clinic name');
        return false;
      }
      if (!this.clinicForm.controls.city.value.trim()) {
        this.clinicForm.controls.city.markAsTouched();
        this.toast('Enter the city');
        return false;
      }
      if (this.clinicForm.controls.pin.invalid) {
        this.toast('Enter a 6-digit PIN code, or leave it blank');
        return false;
      }
    }
    if (step === 3) {
      const owner = this.doctors.at(0);
      if (!owner.controls.name.value.trim()) {
        owner.controls.name.markAsTouched();
        this.toast("Enter the owner doctor's name");
        return false;
      }
      const badFee = this.doctors.controls.some(
        (g) => g.controls.name.value.trim() && !(g.controls.feeRupees.value >= 0),
      );
      if (badFee) {
        this.toast('Enter fees as numbers');
        return false;
      }
    }
    if (step === 5 && this.slugAvailable() !== true) {
      this.toast('Choose an available web address');
      return false;
    }
    return true;
  }

  private bodyForStep(step: number) {
    switch (step) {
      case 1:
        return this.clinicForm.getRawValue();
      case 2:
        return this.specialtyForm.getRawValue();
      case 3: {
        const raw = this.teamForm.getRawValue();
        return {
          doctors: raw.doctors.filter((d) => d.name.trim()),
          staff: raw.staff.filter((s) => s.name.trim()),
        };
      }
      case 4:
        return this.opdForm.getRawValue();
      default:
        return this.lookForm.getRawValue();
    }
  }

  private handleStepError(error: unknown): void {
    const problem = error instanceof ApiError ? error.problem : null;
    if (problem?.status === 409 && problem.type.includes('plan-limit')) {
      const limits = this.limits();
      this.toast(
        `You are on the Free plan: up to ${limits.doctors} doctors and ${limits.users} users.`,
      );
      return;
    }
    if (problem?.status === 409) {
      this.toast(problem.detail ?? 'That web address is already taken.');
      return;
    }
    const fieldMessage = problem?.fieldErrors?.[0]?.message;
    this.toast(fieldMessage ?? problem?.detail ?? 'We could not save this step. Try again.');
  }

  private checkSlug(slug: string, fromEdit: boolean): void {
    if (!/^[a-z0-9][a-z0-9-]{2,23}$/.test(slug)) {
      this.slugAvailable.set(null);
      this.slugAlternatives.set([]);
      return;
    }
    if (!fromEdit && !this.slugEdited) {
      // Resume: still worth a check so the preview pill is accurate.
    }
    this.slugChecking.set(true);
    this.api.checkSlugAvailability(slug).subscribe({
      next: (result) => {
        this.slugChecking.set(false);
        this.slugAvailable.set(result.available);
        this.slugAlternatives.set(result.alternatives);
      },
      error: () => this.slugChecking.set(false),
    });
  }

  private toast(message: string): void {
    this.snackBar.open(message, 'Close', { duration: 5000 });
  }

  private applyAnswers(answers: SetupAnswers | undefined): void {
    if (!answers) {
      return;
    }
    if (answers.clinic) {
      this.clinicForm.patchValue(answers.clinic);
    }
    if (answers.specialty?.code) {
      this.specialtyForm.controls.code.setValue(answers.specialty.code);
    }
    if (answers.team) {
      if (answers.team.doctors?.length) {
        this.doctors.clear();
        answers.team.doctors.forEach((doctor) => {
          const group = doctorGroup();
          group.patchValue(doctor);
          this.doctors.push(group);
        });
      }
      if (answers.team.staff?.length) {
        this.staff.clear();
        answers.team.staff.forEach((member) => {
          const group = staffGroup();
          group.patchValue(member);
          this.staff.push(group);
        });
      }
    }
    if (answers.opd) {
      this.opdForm.patchValue(answers.opd);
    }
    if (answers.look) {
      this.lookForm.patchValue(answers.look);
      if (answers.look.slug) {
        this.slugEdited = true;
      }
    }
  }
}
