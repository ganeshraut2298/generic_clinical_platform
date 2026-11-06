import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnInit,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { ApiError } from '../../../core/http/problem-detail';
import { newClientKey } from '../../../shared/client-key';
import { IconComponent } from '../../../shared/icon/icon.component';
import { problemMessage } from '../../config/config-format';
import { EmergencyApi } from './emergency.api';
import {
  EmergencyLevel,
  EmergencyQueue,
  EmergencySex,
  RegistrationDraft,
  RegistrationErrors,
  RegistrationField,
  RELOAD_AND_RETRY,
  SEX_CHOICES,
  TriageLevel,
  levelHint,
  toCaseInput,
  validateRegistration,
} from './emergency.model';

const SAVE_ERROR = 'We could not add this patient to the queue. What you typed is kept. Try again.';
const FIELD_ORDER: readonly RegistrationField[] = ['name', 'age', 'mobile', 'complaint'];
const FIELD_IDS: Record<RegistrationField, string> = {
  name: 'er-name',
  age: 'er-age',
  mobile: 'er-mobile',
  complaint: 'er-complaint',
};

/**
 * Quick registration panel (SAH-28, mockup `ipEr` form, `erfseg`, `erreg`). Name, age, sex, mobile,
 * complaint and a triage level go to `POST /ipd/emergency/cases` with a client-made key: the same
 * key is sent again on a retry of the same data (AC13), and a new key is made after a success or
 * when any field is edited. The button is off while the request runs, and what was typed stays when
 * it fails.
 */
@Component({
  selector: 'app-emergency-register',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatInputModule,
    IconComponent,
  ],
  styleUrl: './emergency-register.component.scss',
  templateUrl: './emergency-register.component.html',
})
export class EmergencyRegisterComponent implements OnInit {
  private readonly api = inject(EmergencyApi);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly queue = input.required<EmergencyQueue>();
  /** The new queue after a registration (with `actedCase`). */
  readonly registered = output<EmergencyQueue>();
  /** The server answered 409 (for example `ipd-readonly`): the page should reload the queue. */
  readonly conflict = output<void>();

  protected readonly sexChoices = SEX_CHOICES;
  protected readonly hint = levelHint;

  protected readonly form = new FormGroup({
    unknown: new FormControl(false, { nonNullable: true }),
    name: new FormControl('', { nonNullable: true }),
    age: new FormControl('', { nonNullable: true }),
    sex: new FormControl<EmergencySex>('M', { nonNullable: true }),
    mobile: new FormControl('', { nonNullable: true }),
    complaint: new FormControl('', { nonNullable: true }),
    level: new FormControl<TriageLevel>(2, { nonNullable: true }),
  });

  protected readonly running = signal(false);
  protected readonly errors = signal<RegistrationErrors>({});
  protected readonly formError = signal<string | null>(null);
  private readonly key = signal(newClientKey());

  protected readonly unknownAllowed = computed(() => this.queue().unknownPatientRegistration);
  private readonly values = signal(this.form.getRawValue());
  protected readonly nameHidden = computed(() => this.unknownAllowed() && this.values().unknown);
  /** The chosen level, kept inside the scale in use. */
  protected readonly level = computed(() =>
    Math.min(this.values().level, this.queue().levels.length || 1),
  );

  constructor() {
    this.form.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => {
      this.values.set(this.form.getRawValue());
      this.key.set(newClientKey());
    });
  }

  ngOnInit(): void {
    this.form.controls.level.setValue(this.queue().defaultLevel);
  }

  protected setSex(sex: EmergencySex): void {
    this.form.controls.sex.setValue(sex);
  }

  protected setLevel(level: EmergencyLevel): void {
    this.form.controls.level.setValue(level.level);
  }

  protected submit(): void {
    if (this.running()) {
      return;
    }
    const draft = this.draft();
    const errors = validateRegistration(draft, this.unknownAllowed());
    this.errors.set(errors);
    this.formError.set(null);
    const first = FIELD_ORDER.find((field) => errors[field]);
    if (first) {
      this.host.nativeElement.querySelector<HTMLElement>(`#${FIELD_IDS[first]}`)?.focus();
      return;
    }
    this.running.set(true);
    this.api.register(toCaseInput(draft, this.key())).subscribe({
      next: (queue) => {
        // A full case or a reference (repeat of the key): the form is cleared and a new key made either way.
        // The page shows the toast only for a full case; the reference has no name to read.
        this.running.set(false);
        this.clear(queue.defaultLevel);
        this.registered.emit(queue);
      },
      error: (error: unknown) => this.fail(error),
    });
  }

  private draft(): RegistrationDraft {
    const v = this.form.getRawValue();
    return { ...v, unknown: v.unknown && this.unknownAllowed(), level: this.level() as TriageLevel };
  }

  /** Empties the form (level back to the default level). A new key is made by the value change. */
  private clear(defaultLevel: TriageLevel): void {
    this.form.reset({
      unknown: false,
      name: '',
      age: '',
      sex: 'M',
      mobile: '',
      complaint: '',
      level: defaultLevel,
    });
    this.errors.set({});
    this.formError.set(null);
  }

  private fail(error: unknown): void {
    this.running.set(false);
    const fieldErrors = error instanceof ApiError ? error.fieldErrors : [];
    const shown: RegistrationErrors = {};
    const others: string[] = [];
    for (const item of fieldErrors) {
      if (FIELD_ORDER.includes(item.field as RegistrationField)) {
        shown[item.field as RegistrationField] = item.message;
      } else if (item.field === 'identityUnknown') {
        // Not tied to a visible control: a general notice; the typed values and the key stay.
        others.unshift(item.message || RELOAD_AND_RETRY);
      } else {
        others.push(item.message);
      }
    }
    this.errors.set(shown);
    this.formError.set(others[0] ?? (Object.keys(shown).length ? null : problemMessage(error, SAVE_ERROR)));
    if (error instanceof ApiError && error.status === 409) {
      this.conflict.emit();
    }
  }
}
