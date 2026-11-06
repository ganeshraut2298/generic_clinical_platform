import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { DomSanitizer, SafeUrl } from '@angular/platform-browser';
import { Router, RouterLink } from '@angular/router';
import { IconComponent } from '../../../shared/icon/icon.component';
import {
  AdminAuthResult,
  AdminLoginResult,
  AdminNext,
  TotpEnrolment,
  TotpVerifyBody,
} from '../auth/admin-auth.model';
import { AdminAuthService } from '../auth/admin-auth.service';
import {
  CARD_HINT,
  CODE_TOAST,
  EMAIL_ERROR,
  PASSWORD_ERROR,
  RECOVERY_FORMAT,
  RECOVERY_REQUIRED,
  SESSION_ENDED,
  SIDE_HEADING,
  SIDE_LEDE,
  SIDE_PILL,
  SIDE_TICKS,
  describeSignInFailure,
} from '../auth/admin-sign-in-copy';

export type AdminSignInStep = 'credentials' | 'enrol' | 'recovery-codes';

type Factor =
  | { kind: 'none' }
  | { kind: 'code'; code: string }
  | { kind: 'recovery'; code: string }
  | { kind: 'invalid' };

const SIX_DIGITS = /^[0-9]{6}$/;
const RECOVERY_PATTERN = /^[A-Za-z2-7]{4}-?[A-Za-z2-7]{4}-?[A-Za-z2-7]{4}-?[A-Za-z2-7]{4}$/;
const OTPAUTH_PATTERN = /^otpauth:\/\/totp\/[^\s<>"']+$/;

function stripSpaces(value: string): string {
  return value.replace(/\s+/g, '');
}

/**
 * Sahaj admin sign-in (ADM-1), mockup `pAdminLogin`: one card with work email, password and the
 * authenticator code behind one button. A first sign-in continues on "Set up your authenticator
 * app" and then "Save your recovery codes" (ADR-003 D2.6, D2.7).
 *
 * The pre-auth token, the enrolment key and the recovery codes live in this component's memory
 * only. Nothing here is logged, stored or put in a URL.
 */
@Component({
  selector: 'app-admin-sign-in',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    RouterLink,
    IconComponent,
  ],
  styleUrl: './admin-sign-in.page.scss',
  templateUrl: './admin-sign-in.page.html',
})
export class AdminSignInPage {
  private readonly auth = inject(AdminAuthService);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);
  private readonly sanitizer = inject(DomSanitizer);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  private readonly destroyRef = inject(DestroyRef);

  /** From the query string: `?reason=ended` after the session ended. */
  readonly reason = input<string>();

  protected readonly sidePill = SIDE_PILL;
  protected readonly sideHeading = SIDE_HEADING;
  protected readonly sideLede = SIDE_LEDE;
  protected readonly sideTicks = SIDE_TICKS;
  protected readonly cardHint = CARD_HINT;
  protected readonly emailError = EMAIL_ERROR;
  protected readonly passwordError = PASSWORD_ERROR;
  protected readonly recoveryFormat = RECOVERY_FORMAT;
  protected readonly sessionEnded = SESSION_ENDED;

  protected readonly step = signal<AdminSignInStep>('credentials');
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly useRecovery = signal(false);
  protected readonly enrolment = signal<TotpEnrolment | null>(null);
  protected readonly recoveryCodes = signal<string[]>([]);

  protected readonly showEnded = computed(
    () => this.reason() === 'ended' && this.step() === 'credentials' && !this.error(),
  );

  /** `otpauth:` is not on Angular's URL allow-list, so the one link is checked and trusted here. */
  protected readonly otpauthLink = computed<SafeUrl | null>(() => {
    const uri = this.enrolment()?.otpauthUri;
    return uri && OTPAUTH_PATTERN.test(uri) ? this.sanitizer.bypassSecurityTrustUrl(uri) : null;
  });

  protected readonly form = new FormGroup({
    email: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.email, Validators.maxLength(254)],
    }),
    password: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(128)],
    }),
    code: new FormControl('', { nonNullable: true }),
    recoveryCode: new FormControl('', { nonNullable: true }),
  });

  protected readonly enrolForm = new FormGroup({
    code: new FormControl('', {
      nonNullable: true,
      validators: [(control) => (SIX_DIGITS.test(stripSpaces(control.value ?? '')) ? null : { code: true })],
    }),
  });

  /** Kept from step 1 when the code was left empty, so the next press sends only the code. */
  private preAuthToken: string | null = null;
  private preAuthNext: AdminNext = 'TOTP';
  private preAuthExpiresAt = 0;

  constructor() {
    // A different email or password means a different attempt: forget the pre-auth token.
    this.form.controls.email.valueChanges
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.forgetPreAuth());
    this.form.controls.password.valueChanges
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.forgetPreAuth());
    this.focusLater('#admin-email');
  }

  protected toggleRecovery(): void {
    this.useRecovery.update((value) => !value);
    this.error.set(null);
    this.form.controls.code.reset('');
    this.form.controls.recoveryCode.reset('');
    this.focusLater(this.useRecovery() ? '#admin-recovery' : '#admin-code');
  }

  protected submit(): void {
    if (this.busy()) {
      return;
    }
    const { email, password } = this.form.controls;
    email.markAsTouched();
    password.markAsTouched();
    if (email.invalid) {
      this.focusLater('#admin-email');
      return;
    }
    if (password.invalid) {
      this.focusLater('#admin-password');
      return;
    }
    const factor = this.readFactor();
    if (factor.kind === 'invalid') {
      return;
    }
    this.error.set(null);

    const held = this.heldPreAuth();
    if (held) {
      this.afterLogin(held, factor, false);
      return;
    }
    this.busy.set(true);
    this.auth.login(email.value, password.value).subscribe({
      next: (result) => this.afterLogin(result, factor, true),
      error: (error: unknown) => this.fail(error),
    });
  }

  protected confirmEnrolment(): void {
    if (this.busy() || !this.preAuthToken) {
      return;
    }
    const code = stripSpaces(this.enrolForm.controls.code.value);
    if (!SIX_DIGITS.test(code)) {
      this.enrolForm.controls.code.markAsTouched();
      this.focusLater('#admin-enrol-code');
      return;
    }
    this.error.set(null);
    this.verify({ preAuthToken: this.preAuthToken, code });
  }

  protected startAgain(): void {
    this.forgetPreAuth();
    this.enrolment.set(null);
    this.recoveryCodes.set([]);
    this.error.set(null);
    this.useRecovery.set(false);
    this.form.reset();
    this.enrolForm.reset();
    this.step.set('credentials');
    this.focusLater('#admin-email');
  }

  protected copyKey(): void {
    const key = this.enrolment()?.secretKey;
    if (key) {
      this.copy(stripSpaces(key), 'Key copied');
    }
  }

  protected copyCodes(): void {
    const codes = this.recoveryCodes();
    if (codes.length > 0) {
      this.copy(codes.join('\n'), 'Recovery codes copied');
    }
  }

  protected saved(): void {
    this.recoveryCodes.set([]);
    this.enrolment.set(null);
    void this.router.navigate(['/admin/clinics']);
  }

  private afterLogin(
    result: AdminLoginResult,
    factor: Exclude<Factor, { kind: 'invalid' }>,
    fresh: boolean,
  ): void {
    if (fresh) {
      this.preAuthToken = result.preAuthToken;
      this.preAuthNext = result.next;
      this.preAuthExpiresAt = Date.now() + result.expiresInSeconds * 1000;
    }
    if (result.next === 'ENROL') {
      this.startEnrolment(result.preAuthToken);
      return;
    }
    if (factor.kind === 'none') {
      this.busy.set(false);
      this.toastError(this.useRecovery() ? RECOVERY_REQUIRED : CODE_TOAST);
      this.focusLater(this.useRecovery() ? '#admin-recovery' : '#admin-code');
      return;
    }
    this.busy.set(true);
    this.verify(
      factor.kind === 'code'
        ? { preAuthToken: result.preAuthToken, code: factor.code }
        : { preAuthToken: result.preAuthToken, recoveryCode: factor.code },
    );
  }

  private startEnrolment(preAuthToken: string): void {
    this.busy.set(true);
    this.auth.enrol(preAuthToken).subscribe({
      next: (enrolment) => {
        this.busy.set(false);
        this.enrolment.set(enrolment);
        this.form.controls.password.reset('', { emitEvent: false });
        this.form.controls.code.reset('');
        this.form.controls.recoveryCode.reset('');
        this.enrolForm.reset();
        this.step.set('enrol');
        this.focusLater('#admin-step-heading');
      },
      error: (error: unknown) => this.fail(error),
    });
  }

  private verify(body: TotpVerifyBody): void {
    this.busy.set(true);
    this.auth.verify(body).subscribe({
      next: (result) => this.signedIn(result),
      error: (error: unknown) => this.fail(error),
    });
  }

  private signedIn(result: AdminAuthResult): void {
    this.busy.set(false);
    this.forgetPreAuth();
    this.form.reset();
    this.enrolForm.reset();
    this.snackBar.open(`Signed in as ${result.admin.name} · ${result.admin.roleLabel}`, undefined, {
      duration: 4000,
    });
    if (result.recoveryCodes && result.recoveryCodes.length > 0) {
      this.recoveryCodes.set(result.recoveryCodes);
      this.step.set('recovery-codes');
      this.focusLater('#admin-step-heading');
      return;
    }
    void this.router.navigate(['/admin/clinics']);
  }

  private fail(error: unknown): void {
    this.busy.set(false);
    const failure = describeSignInFailure(error);
    this.error.set(failure.message);
    if (failure.reset) {
      this.forgetPreAuth();
      this.form.controls.code.reset('');
      this.form.controls.recoveryCode.reset('');
      if (this.step() !== 'credentials') {
        this.enrolment.set(null);
        this.enrolForm.reset();
        this.form.controls.password.reset('');
        this.step.set('credentials');
        this.focusLater('#admin-step-heading');
      }
    }
  }

  private readFactor(): Factor {
    const { code, recoveryCode } = this.form.controls;
    if (this.useRecovery()) {
      const value = stripSpaces(recoveryCode.value);
      if (value === '') {
        return { kind: 'none' };
      }
      if (!RECOVERY_PATTERN.test(value)) {
        this.error.set(RECOVERY_FORMAT);
        this.focusLater('#admin-recovery');
        return { kind: 'invalid' };
      }
      return { kind: 'recovery', code: value };
    }
    const value = stripSpaces(code.value);
    if (value === '') {
      return { kind: 'none' };
    }
    if (!SIX_DIGITS.test(value)) {
      this.toastError(CODE_TOAST);
      this.focusLater('#admin-code');
      return { kind: 'invalid' };
    }
    return { kind: 'code', code: value };
  }

  private heldPreAuth(): AdminLoginResult | null {
    if (!this.preAuthToken || Date.now() >= this.preAuthExpiresAt) {
      return null;
    }
    return { next: this.preAuthNext, preAuthToken: this.preAuthToken, expiresInSeconds: 0 };
  }

  private forgetPreAuth(): void {
    this.preAuthToken = null;
    this.preAuthExpiresAt = 0;
  }

  private toastError(message: string): void {
    this.snackBar.open(message, undefined, { duration: 5000 });
  }

  private copy(text: string, done: string): void {
    const clipboard = globalThis.navigator?.clipboard;
    if (!clipboard) {
      this.toastError('Could not copy. Select the text and copy it.');
      return;
    }
    clipboard.writeText(text).then(
      () => this.snackBar.open(done, undefined, { duration: 2500 }),
      () => this.toastError('Could not copy. Select the text and copy it.'),
    );
  }

  private focusLater(selector: string): void {
    afterNextRender(
      () => this.host.nativeElement.querySelector<HTMLElement>(selector)?.focus(),
      { injector: this.injector },
    );
  }
}
