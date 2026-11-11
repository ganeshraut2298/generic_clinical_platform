import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  afterNextRender,
  inject,
  signal,
} from '@angular/core';
import {
  AbstractControl,
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  ValidationErrors,
} from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { Router, RouterLink } from '@angular/router';
import { IconComponent } from '../../../shared/icon/icon.component';
import { AdminBootstrapApi } from './admin-bootstrap.api';
import {
  CREATE_HEADING,
  EMAIL_ERROR,
  EMAIL_MAX,
  NAME_ERROR,
  NAME_MAX,
  PASSWORDS_DIFFER,
  PASSWORD_ERROR,
  PASSWORD_HINT,
  PASSWORD_MAX,
  PASSWORD_MIN,
  PIN_BLANK,
  RegisterField,
  SIDE_HEADING,
  SIDE_LEDE,
  SIDE_PILL,
  SOMETHING_WENT_WRONG,
  UNLOCK_EXPIRED,
  UNLOCK_HEADING,
  UNLOCK_HINT,
  describeBootstrapFailure,
  lockedMessage,
} from './admin-register-copy';

export type AdminRegisterStep = 'unlock' | 'create';

type FieldName = RegisterField | 'pin' | 'confirm';

const FIELD_ID: Record<Exclude<FieldName, 'pin'>, string> = {
  fullName: 'reg-name',
  email: 'reg-email',
  password: 'reg-password',
  confirm: 'reg-confirm',
};

const EMAIL_SHAPE = /^[^\s@]+@[^\s@]+$/;

function notBlank(control: AbstractControl<string>): ValidationErrors | null {
  return (control.value ?? '').trim() === '' ? { blank: true } : null;
}

function fullName(control: AbstractControl<string>): ValidationErrors | null {
  const value = (control.value ?? '').trim();
  return value === '' || value.length > NAME_MAX ? { name: true } : null;
}

function email(control: AbstractControl<string>): ValidationErrors | null {
  const value = (control.value ?? '').trim();
  return EMAIL_SHAPE.test(value) && value.length <= EMAIL_MAX ? null : { email: true };
}

function password(control: AbstractControl<string>): ValidationErrors | null {
  const length = (control.value ?? '').length;
  return length >= PASSWORD_MIN && length <= PASSWORD_MAX ? null : { password: true };
}

/**
 * Admin bootstrap registration (SAH-42, DEC-013 item 16): a minimal reuse of the mockup
 * `pAdminLogin` card, in two steps. The Unlock card asks for the PIN and shows nothing else; after
 * a correct PIN the Create the first admin card shows. The server answers an empty 404 when the
 * feature is off or closed, and the page then goes where the app sends any unknown address (the
 * home page).
 *
 * The PIN is read from its field, the field is cleared in the same step, and the value only
 * travels in the request body: it is never put in component state, a URL, storage or history. The
 * ticket (the other half of the binding is an HttpOnly cookie the page cannot read) and the
 * password live in memory while a card is open and are dropped after use.
 */
@Component({
  selector: 'app-admin-register',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    RouterLink,
    IconComponent,
  ],
  // The card, side panel and field styles are the ADM-1 sign-in page's own.
  styleUrl: '../sign-in/admin-sign-in.page.scss',
  templateUrl: './admin-register.page.html',
})
export class AdminRegisterPage {
  private readonly api = inject(AdminBootstrapApi);
  private readonly router = inject(Router);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly sidePill = SIDE_PILL;
  protected readonly sideHeading = SIDE_HEADING;
  protected readonly sideLede = SIDE_LEDE;
  protected readonly unlockHeading = UNLOCK_HEADING;
  protected readonly unlockHint = UNLOCK_HINT;
  protected readonly createHeading = CREATE_HEADING;
  protected readonly passwordHint = PASSWORD_HINT;

  protected readonly step = signal<AdminRegisterStep>('unlock');
  protected readonly busy = signal(false);
  /** One line for the whole card (wrong PIN, offline, expired, locked, safety net). */
  protected readonly error = signal<string | null>(null);
  /** Minutes left of the 60-minute lock, or null when not locked. */
  protected readonly lockedMinutes = signal<number | null>(null);
  private readonly serverErrors = signal<Partial<Record<FieldName, string>>>({});

  protected readonly unlockForm = new FormGroup({
    pin: new FormControl('', { nonNullable: true, validators: [notBlank] }),
  });

  private readonly passwordControl = new FormControl('', {
    nonNullable: true,
    validators: [password],
  });

  protected readonly createForm = new FormGroup({
    fullName: new FormControl('', { nonNullable: true, validators: [fullName] }),
    email: new FormControl('', { nonNullable: true, validators: [email] }),
    password: this.passwordControl,
    confirm: new FormControl('', {
      nonNullable: true,
      validators: [(control) => (control.value === this.passwordControl.value ? null : { differ: true })],
    }),
  });

  /** Single-use ticket from the unlock call. Memory only, dropped after use. */
  private ticket: string | null = null;

  constructor() {
    this.unlockForm.controls.pin.valueChanges
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.forget('pin'));
    for (const name of ['fullName', 'email', 'password', 'confirm'] as const) {
      this.createForm.controls[name].valueChanges
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe(() => this.forget(name));
    }
    this.createForm.controls.password.valueChanges
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.createForm.controls.confirm.updateValueAndValidity({ emitEvent: false }));
    this.destroyRef.onDestroy(() => this.dropEverything());
    this.focusLater('#reg-pin');
  }

  protected fieldError(name: FieldName): string | null {
    const server = this.serverErrors()[name];
    if (server) {
      return server;
    }
    const control = name === 'pin' ? this.unlockForm.controls.pin : this.createForm.controls[name];
    if (!control.touched || control.valid) {
      return null;
    }
    switch (name) {
      case 'pin':
        return PIN_BLANK;
      case 'fullName':
        return NAME_ERROR;
      case 'email':
        return EMAIL_ERROR;
      case 'password':
        return PASSWORD_ERROR;
      case 'confirm':
        return PASSWORDS_DIFFER;
    }
  }

  protected unlock(): void {
    const pin = this.unlockForm.controls.pin;
    if (this.busy() || this.lockedMinutes() !== null) {
      return;
    }
    pin.markAsTouched();
    if (pin.invalid) {
      this.focusLater('#reg-pin');
      return;
    }
    // Read once, clear the field now: from here the PIN exists only in the request body.
    const value = pin.value;
    pin.reset('', { emitEvent: false });
    this.error.set(null);
    this.serverErrors.set({});
    this.busy.set(true);
    // Cancelled on destroy: a late answer must not store a ticket on a destroyed page.
    this.api
      .unlock({ pin: value })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          this.busy.set(false);
          this.ticket = result.ticket;
          this.step.set('create');
          this.focusLater('#reg-name');
        },
        error: (failure: unknown) => this.unlockFailed(failure),
      });
  }

  protected create(): void {
    const form = this.createForm;
    if (this.busy() || !this.ticket) {
      return;
    }
    form.markAllAsTouched();
    form.controls.confirm.updateValueAndValidity({ emitEvent: false });
    const invalid = (['fullName', 'email', 'password', 'confirm'] as const).find(
      (name) => form.controls[name].invalid,
    );
    if (invalid) {
      this.focusLater(`#${FIELD_ID[invalid]}`);
      return;
    }
    const body = {
      ticket: this.ticket,
      fullName: form.controls.fullName.value.trim(),
      email: form.controls.email.value.trim(),
      password: form.controls.password.value,
    };
    // The password is not kept after the call: clear both fields as it is sent.
    this.clearPasswords();
    this.error.set(null);
    this.serverErrors.set({});
    this.busy.set(true);
    // Cancelled on destroy: a late answer must not navigate the owner away from where they went.
    this.api
      .register(body)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => this.created(),
        error: (failure: unknown) => this.createFailed(failure),
      });
  }

  private created(): void {
    this.busy.set(false);
    this.dropEverything();
    // The admin exists and the registration is closed. The 201 has no body (ADR-018 Revision 5):
    // the owner finishes the authenticator set-up at the normal sign-in (SAH-42-5 criteria 2
    // and 9). See docs/handoffs/SAH-42.md, angular-developer entry.
    void this.router.navigate(['/admin/sign-in']);
  }

  private unlockFailed(failure: unknown): void {
    this.busy.set(false);
    const result = describeBootstrapFailure(failure);
    switch (result.kind) {
      case 'closed':
        this.showPageNotFound();
        return;
      case 'locked':
        this.lock(result.minutes);
        return;
      case 'fields':
        this.serverErrors.set({ pin: result.fields.pin ?? PIN_BLANK });
        this.unlockForm.controls.pin.markAsTouched();
        this.focusLater('#reg-pin');
        return;
      case 'wrong-pin':
      case 'message':
        this.error.set(result.message);
        this.focusLater('#reg-pin');
        return;
      case 'ticket-expired':
        this.error.set(SOMETHING_WENT_WRONG);
        this.focusLater('#reg-pin');
        return;
    }
  }

  private createFailed(failure: unknown): void {
    this.busy.set(false);
    const result = describeBootstrapFailure(failure);
    switch (result.kind) {
      case 'closed':
        this.showPageNotFound();
        return;
      case 'locked':
        this.backToUnlock();
        this.lock(result.minutes);
        return;
      case 'ticket-expired':
        this.backToUnlock();
        this.error.set(UNLOCK_EXPIRED);
        this.focusLater('#reg-pin');
        return;
      case 'fields': {
        const { fields } = result;
        this.serverErrors.set({ ...fields });
        for (const name of ['fullName', 'email', 'password'] as const) {
          if (fields[name]) {
            this.createForm.controls[name].markAsTouched();
            this.createForm.controls[name].setErrors({ server: true });
          }
        }
        const first = (['fullName', 'email', 'password'] as const).find((name) => fields[name]);
        this.focusLater(first ? `#${FIELD_ID[first]}` : '#reg-name');
        return;
      }
      case 'wrong-pin':
      case 'message':
        this.error.set(result.message);
        this.focusLater('#reg-name');
        return;
    }
  }

  private lock(minutes: number): void {
    this.lockedMinutes.set(minutes);
    this.error.set(lockedMessage(minutes));
    this.unlockForm.disable({ emitEvent: false });
    // The focused PIN field is now disabled, so focus would fall to the page: put it on the card
    // heading (tabindex -1) so a screen reader announces the locked state in place.
    this.focusLater('#reg-step-heading');
  }

  /** The ticket is gone (expired or cancelled): forget the form and show the Unlock card. */
  private backToUnlock(): void {
    this.dropEverything();
    this.step.set('unlock');
  }

  private showPageNotFound(): void {
    this.dropEverything();
    // No page of this name exists, so the router's normal wildcard answer applies, exactly as
    // for any unknown address. replaceUrl keeps /admin/register out of the history.
    void this.router.navigateByUrl('/page-not-found', { replaceUrl: true });
  }

  /** Clears every secret the page held: PIN field, ticket, password, name and email. */
  private dropEverything(): void {
    this.ticket = null;
    this.unlockForm.reset({ pin: '' }, { emitEvent: false });
    this.createForm.reset({ fullName: '', email: '', password: '', confirm: '' }, { emitEvent: false });
    this.serverErrors.set({});
  }

  private clearPasswords(): void {
    const { password: pass, confirm } = this.createForm.controls;
    pass.reset('', { emitEvent: false });
    confirm.reset('', { emitEvent: false });
  }

  private forget(name: FieldName): void {
    if (this.serverErrors()[name]) {
      this.serverErrors.update((errors) => ({ ...errors, [name]: undefined }));
    }
  }

  private focusLater(selector: string): void {
    afterNextRender(() => this.host.nativeElement.querySelector<HTMLElement>(selector)?.focus(), {
      injector: this.injector,
    });
  }
}
