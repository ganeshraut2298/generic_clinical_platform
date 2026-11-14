import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Router, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { ShellStore } from '../../../../core/shell/shell.store';
import { IconComponent } from '../../../../shared/icon/icon.component';
import { EmrApi } from '../../../emr/emr.api';
import { detailOr, problemSlug } from '../../../emr/emr-errors';
import {
  EmrDepartmentSummary,
  EmrDoctorOption,
  EmrStarterDetail,
  EmrStarterSummary,
  StarterKey,
} from '../../../emr/emr.model';
import { EmrConfigStore } from '../emr-config.store';
import { SectionSettingsComponent } from '../section-settings.component';
import { SectionsColumnComponent } from '../sections-column.component';
import {
  NAME_MAX,
  SourceChoice,
  createBody,
  previewSections,
  previewView,
  roomProblem,
  turnedOffLine,
  withPrintFlags,
} from './add-department.logic';

type Step = 1 | 2 | 3;

const LOAD_ERROR = 'We could not load the templates. Try again.';
const CREATE_ERROR = 'We could not create the department. Your choices are kept. Try again.';

/**
 * Add a department (SAH-54, mockup #AddDepartment): step 1 chooses a blank start, a copy of one of
 * the clinic's departments or a starter template; step 2 reviews the sections with the SAH-45
 * columns; step 3 assigns doctors and rooms. Steps 2 and 3 are not drawn in the mockup (SO-45-21).
 * Nothing is created until the owner confirms. Only the owner can use it; the server checks again.
 */
@Component({
  selector: 'app-add-department',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    RouterLink,
    IconComponent,
    SectionsColumnComponent,
    SectionSettingsComponent,
  ],
  providers: [EmrConfigStore],
  styleUrl: './add-department.page.scss',
  templateUrl: './add-department.page.html',
})
export class AddDepartmentPage implements OnInit {
  private readonly api = inject(EmrApi);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);
  private readonly shell = inject(ShellStore);
  protected readonly review = inject(EmrConfigStore);

  protected readonly isOwner = computed(() => this.shell.me()?.role === 'OWNER');
  protected readonly nameMax = NAME_MAX;

  protected readonly step = signal<Step>(1);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);

  protected readonly starters = signal<EmrStarterSummary[]>([]);
  /** Departments the clinic has saved: the only ones that can be copied (the server says 404 otherwise). */
  protected readonly copyable = signal<EmrDepartmentSummary[]>([]);
  private readonly details = signal<Record<string, EmrStarterDetail>>({});

  protected readonly choice = signal<SourceChoice | null>(null);
  protected readonly copyOpen = signal(false);
  protected readonly copyPrint = signal(true);
  protected readonly name = new FormControl('', {
    nonNullable: true,
    validators: [Validators.required, Validators.maxLength(NAME_MAX), Validators.pattern(/\S/)],
  });
  /** The typed name as a signal, so the buttons follow it. */
  private readonly nameText = toSignal(this.name.valueChanges, { initialValue: '' });
  protected readonly nameValid = computed(() => {
    const text = this.nameText().trim();
    return text.length > 0 && text.length <= NAME_MAX;
  });
  protected readonly nameTouched = signal(false);
  protected readonly nameTaken = signal<string | null>(null);
  protected readonly previewLoading = signal(false);

  protected readonly reviewLoading = signal(false);
  protected readonly reviewError = signal<string | null>(null);
  private seededWith: string | null = null;

  protected readonly doctors = signal<EmrDoctorOption[]>([]);
  protected readonly doctorsError = signal<string | null>(null);
  protected readonly pickedDoctors = signal<ReadonlySet<string>>(new Set());
  protected readonly rooms = signal<string[]>([]);
  protected readonly roomInput = new FormControl('', { nonNullable: true });
  protected readonly roomError = signal<string | null>(null);

  protected readonly creating = signal(false);
  protected readonly createError = signal<string | null>(null);

  protected readonly selectedStarter = computed(() => {
    const c = this.choice();
    return c?.kind === 'STARTER' ? (this.starters().find((s) => s.starterKey === c.starterKey) ?? null) : null;
  });
  protected readonly selectedDetail = computed(() => {
    const c = this.choice();
    return c?.kind === 'STARTER' ? (this.details()[c.starterKey] ?? null) : null;
  });
  protected readonly previewNames = computed(() => {
    const d = this.selectedDetail();
    return d ? previewSections(d) : [];
  });
  protected readonly offLine = computed(() => turnedOffLine(this.selectedStarter()?.offAtStart ?? []));
  protected readonly copySourceName = computed(() => {
    const c = this.choice();
    return c?.kind === 'COPY'
      ? (this.copyable().find((d) => d.departmentKey === c.copyFromDepartmentKey)?.name ?? '')
      : '';
  });
  protected readonly nameInvalid = computed(() => this.nameTouched() && !this.nameValid());
  protected readonly canNext = computed(() => this.choice() !== null && this.nameValid());

  ngOnInit(): void {
    forkJoin({ starters: this.api.listStarters(), departments: this.api.listDepartments() }).subscribe({
      next: ({ starters, departments }) => {
        this.starters.set(starters);
        this.copyable.set(departments.departments.filter((d) => d.status !== 'TEMPLATE'));
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.loadError.set(LOAD_ERROR);
      },
    });
    this.name.valueChanges.subscribe(() => this.nameTaken.set(null));
  }

  protected chooseBlank(): void {
    this.choice.set({ kind: 'BLANK' });
    this.copyOpen.set(false);
  }

  protected toggleCopy(): void {
    if (this.copyable().length === 0) {
      return;
    }
    this.copyOpen.update((open) => !open);
    const first = this.copyable()[0];
    if (!this.copyOpen()) {
      return;
    }
    if (this.choice()?.kind !== 'COPY') {
      this.choice.set({ kind: 'COPY', copyFromDepartmentKey: first.departmentKey });
    }
  }

  protected chooseCopy(key: string): void {
    this.choice.set({ kind: 'COPY', copyFromDepartmentKey: key });
  }

  protected chooseStarter(starterKey: StarterKey): void {
    this.copyOpen.set(false);
    this.choice.set({ kind: 'STARTER', starterKey });
    if (this.details()[starterKey]) {
      return;
    }
    this.previewLoading.set(true);
    this.api.getStarter(starterKey).subscribe({
      next: (detail) => {
        this.details.update((d) => ({ ...d, [starterKey]: detail }));
        this.previewLoading.set(false);
      },
      error: () => this.previewLoading.set(false),
    });
  }

  protected isStarter(key: StarterKey): boolean {
    const c = this.choice();
    return c?.kind === 'STARTER' && c.starterKey === key;
  }

  protected isCopy(key: string): boolean {
    const c = this.choice();
    return c?.kind === 'COPY' && c.copyFromDepartmentKey === key;
  }

  protected setCopyPrint(event: Event): void {
    this.copyPrint.set((event.target as HTMLInputElement).checked);
  }

  // ---- steps ----

  protected next(): void {
    this.nameTouched.set(true);
    if (this.step() === 1) {
      if (!this.canNext()) {
        return;
      }
      this.step.set(2);
      this.seedReview();
    } else if (this.step() === 2) {
      this.step.set(3);
      this.loadDoctors();
    }
  }

  protected back(): void {
    if (this.step() > 1) {
      this.step.update((s) => (s - 1) as Step);
    }
  }

  private seedKey(choice: SourceChoice): string {
    const id = choice.kind === 'STARTER' ? choice.starterKey : choice.kind === 'COPY' ? choice.copyFromDepartmentKey : '';
    return `${choice.kind}:${id}:${this.copyPrint()}`;
  }

  /** Step 2 shows the chosen source in the SAH-45 columns; the owner's edits are kept while the choice is unchanged. */
  private seedReview(): void {
    const choice = this.choice();
    if (!choice) {
      return;
    }
    const key = this.seedKey(choice);
    if (key === this.seededWith) {
      if (choice.kind !== 'BLANK') {
        this.review.renameLocal(this.name.value.trim());
      }
      return;
    }
    this.reviewError.set(null);
    const name = this.name.value.trim();
    const done = (view: ReturnType<typeof previewView>): void => {
      this.review.seedLocal(view);
      this.seededWith = key;
      this.reviewLoading.set(false);
    };
    if (choice.kind === 'BLANK') {
      this.seededWith = key;
      return;
    }
    this.reviewLoading.set(true);
    if (choice.kind === 'STARTER') {
      const starter = this.selectedStarter();
      const detail = this.details()[choice.starterKey];
      if (!starter || !detail) {
        this.reviewLoading.set(false);
        this.reviewError.set(LOAD_ERROR);
        return;
      }
      done(previewView(name, starter, starter.starterKey, withPrintFlags(detail.sections, this.copyPrint()), choice));
      return;
    }
    this.api.getDepartment(choice.copyFromDepartmentKey).subscribe({
      next: (source) =>
        done(previewView(name, source, source.starterKey, withPrintFlags(source.sections, this.copyPrint()), choice)),
      error: () => {
        this.reviewLoading.set(false);
        this.reviewError.set(LOAD_ERROR);
      },
    });
  }

  protected retryReview(): void {
    this.seededWith = null;
    this.seedReview();
  }

  private loadDoctors(): void {
    if (this.doctors().length > 0) {
      return;
    }
    this.doctorsError.set(null);
    this.api.listDoctorOptions().subscribe({
      next: (list) => this.doctors.set(list),
      error: () => this.doctorsError.set('We could not load the doctors. Try again.'),
    });
  }

  protected retryDoctors(): void {
    this.loadDoctors();
  }

  protected toggleDoctor(id: string): void {
    this.pickedDoctors.update((set) => {
      const next = new Set(set);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  }

  protected addRoom(): void {
    const problem = roomProblem(this.rooms(), this.roomInput.value);
    this.roomError.set(problem);
    if (problem) {
      return;
    }
    this.rooms.update((r) => [...r, this.roomInput.value.trim()]);
    this.roomInput.setValue('');
  }

  protected removeRoom(room: string): void {
    this.rooms.update((r) => r.filter((x) => x !== room));
    this.roomError.set(null);
  }

  protected onRoomKey(event: Event): void {
    event.preventDefault();
    this.addRoom();
  }

  // ---- create ----

  protected create(): void {
    const choice = this.choice();
    if (!choice || this.creating() || !this.name.valid) {
      return;
    }
    this.creating.set(true);
    this.createError.set(null);
    const changed = choice.kind !== 'BLANK' && this.review.unsaved() > 0 ? this.review.workingSettings() : null;
    const body = createBody({
      name: this.name.value,
      source: choice,
      copyPrintFlags: this.copyPrint(),
      changedSections: changed,
      doctorMembershipIds: [...this.pickedDoctors()],
      rooms: this.rooms(),
    });
    this.api.createDepartment(body).subscribe({
      next: (created) => {
        this.creating.set(false);
        this.snackBar.open(`${created.name} added as a draft. Publish it when you are ready.`, 'Close', {
          duration: 5000,
        });
        void this.router.navigate(['/app/config/specialty'], { queryParams: { department: created.departmentKey } });
      },
      error: (error: unknown) => {
        this.creating.set(false);
        if (problemSlug(error) === 'department-name-taken') {
          this.nameTaken.set(detailOr(error, 'A department with this name already exists.'));
          this.step.set(1);
          return;
        }
        this.createError.set(detailOr(error, CREATE_ERROR));
      },
    });
  }
}
