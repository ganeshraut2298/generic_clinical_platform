import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  OnInit,
  afterNextRender,
  computed,
  effect,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import {
  AbstractControl,
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  ValidationErrors,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { ErrorStateMatcher } from '@angular/material/core';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Router, RouterLink } from '@angular/router';
import { ApiError } from '../../../core/http/problem-detail';
import { ReducedMotion } from '../../../core/motion/reduced-motion';
import { IconComponent } from '../../../shared/icon/icon.component';
import { KineticBackgroundComponent } from '../../../shared/ui/kinetic-background/kinetic-background.component';
import { AuthService } from '../../../core/auth/auth.service';
import { AuthMode, AuthResult, ClinicChoice, MOBILE_PATTERN, PRIVACY_VERSION, TERMS_VERSION } from '../../../core/auth/auth.model';
import {
  ALREADY_HAS_CLINIC_MESSAGE,
  CARD_HEADING,
  CONSENT_LINE,
  LOCK_MESSAGE,
  NOT_REGISTERED_MESSAGE,
  SIDE_COPY,
  SIDE_TICKS,
} from './login-copy';

export type LoginStep = 'mobile' | 'otp' | 'chooseClinic' | 'notRegistered';

const DEFAULT_RESEND_SECONDS = 30;

/** Where the sliding highlight sits behind the selected tab, in px from the left of the group. */
export interface TabThumb {
  x: number;
  width: number;
}

/**
 * A field shows its red error only after the button was pressed with a bad value (mockup
 * `sendotp` and `verify`), never because the user only focused and left it (SAH-74-5).
 */
export class AfterAttemptMatcher implements ErrorStateMatcher {
  constructor(private readonly attempted: () => boolean) {}

  isErrorState(control: AbstractControl | null): boolean {
    return !!control && control.invalid && this.attempted();
  }
}

function digitsOnly(value: string): string {
  return value.replace(/\D/g, '');
}

function mobileValidator(control: AbstractControl<string>): ValidationErrors | null {
  const digits = digitsOnly(control.value ?? '');
  return MOBILE_PATTERN.test(digits) ? null : { mobile: true };
}

function codeValidator(control: AbstractControl<string>): ValidationErrors | null {
  const digits = digitsOnly(control.value ?? '');
  return /^[0-9]{6}$/.test(digits) ? null : { code: true };
}

/** Sign up and log in with mobile one-time code (SAH-3), mockup screen `pLogin`. */
@Component({
  selector: 'app-login',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatInputModule,
    RouterLink,
    IconComponent,
    KineticBackgroundComponent,
  ],
  styleUrl: './login.page.scss',
  templateUrl: './login.page.html',
})
export class LoginPage implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly reducedMotion = inject(ReducedMotion);
  private readonly seg = viewChild<unknown, ElementRef<HTMLElement>>('seg', { read: ElementRef });

  /** From the query string: /login?mode=signup. Anything else is Log in. */
  readonly mode = input<string>();

  protected readonly authMode = signal<AuthMode>('LOGIN');
  protected readonly step = signal<LoginStep>('mobile');
  protected readonly sending = signal(false);
  protected readonly resendSeconds = signal(0);
  protected readonly lockMessage = signal<string | null>(null);
  protected readonly formError = signal<string | null>(null);
  protected readonly clinics = signal<ClinicChoice[]>([]);

  /** True after Send OTP / Verify was pressed; clears when the step restarts. */
  private readonly mobileAttempted = signal(false);
  private readonly codeAttempted = signal(false);
  protected readonly mobileMatcher = new AfterAttemptMatcher(() => this.mobileAttempted());
  protected readonly codeMatcher = new AfterAttemptMatcher(() => this.codeAttempted());

  /**
   * Soft entrance of the left panel and the card (SAH-74-1). On when the visitor has not asked for
   * reduced motion; switches off for good if they do, and does not replay if they switch back.
   */
  protected readonly entrance = signal(!this.reducedMotion.reduced());
  /** Position of the sliding highlight; null until measured (the toggle's own highlight shows). */
  protected readonly thumb = signal<TabThumb | null>(null);
  /** True only while a tab change should slide the highlight. */
  protected readonly thumbSlide = signal(false);

  protected readonly consentLine = CONSENT_LINE;
  protected readonly sideTicks = SIDE_TICKS;

  protected readonly isSignup = computed(() => this.authMode() === 'SIGNUP');
  protected readonly sideCopy = computed(() => SIDE_COPY[this.authMode()]);
  protected readonly cardHeading = computed(() => CARD_HEADING[this.authMode()]);
  protected readonly maskedMobile = computed(() => {
    const digits = digitsOnly(this.mobileControl.value);
    return digits.length === 10 ? `${digits.slice(0, 5)} ${digits.slice(5)}` : digits;
  });
  protected readonly resendLabel = computed(() => {
    const seconds = this.resendSeconds();
    return `0:${String(seconds).padStart(2, '0')}`;
  });

  protected readonly mobileControl = new FormControl('', {
    nonNullable: true,
    validators: [mobileValidator],
  });
  protected readonly codeControl = new FormControl('', {
    nonNullable: true,
    validators: [codeValidator],
  });
  protected readonly consentControl = new FormControl(false, { nonNullable: true });

  // Empty groups only so `[formGroup]` on the `<form>` elements makes `(ngSubmit)` fire on the
  // native submit event (Enter key included). Each field still binds with its own FormControl.
  protected readonly mobileForm = new FormGroup({});
  protected readonly otpForm = new FormGroup({});

  private timer: ReturnType<typeof setInterval> | undefined;

  constructor() {
    this.destroyRef.onDestroy(() => this.stopCountdown());
    effect(() => {
      if (this.reducedMotion.reduced()) {
        this.entrance.set(false);
        this.thumbSlide.set(false);
      }
    });
    effect(() => {
      this.authMode();
      afterNextRender(() => this.placeThumb(true), { injector: this.injector });
    });
    afterNextRender(() => this.watchThumbSize());
  }

  ngOnInit(): void {
    this.authMode.set(this.mode() === 'signup' ? 'SIGNUP' : 'LOGIN');
    // As the mockup does when the page opens: the cursor is in the mobile field.
    this.focus('#login-mobile');
  }

  protected setAuthMode(mode: AuthMode): void {
    this.authMode.set(mode);
    this.resetToMobileStep();
  }

  protected mobileFieldError(): string | null {
    const errors = this.mobileControl.errors;
    if (!errors || !this.mobileAttempted()) {
      return null;
    }
    if (typeof errors['server'] === 'string') {
      return errors['server'];
    }
    return 'Enter a 10-digit mobile number';
  }

  protected codeFieldError(): string | null {
    const errors = this.codeControl.errors;
    if (!errors || !this.codeAttempted()) {
      return null;
    }
    if (typeof errors['server'] === 'string') {
      return errors['server'];
    }
    return 'Enter the 6-digit code';
  }

  protected sendOtp(): void {
    if (this.sending()) {
      return;
    }
    this.mobileControl.markAsTouched();
    this.mobileAttempted.set(true);
    if (this.mobileControl.invalid) {
      this.focus('#login-mobile');
      return;
    }
    this.lockMessage.set(null);
    this.formError.set(null);
    this.sending.set(true);
    const mobile = digitsOnly(this.mobileControl.value);
    this.auth.requestOtp(mobile, this.authMode()).subscribe({
      next: (result) => {
        this.sending.set(false);
        this.codeControl.reset('');
        this.step.set('otp');
        this.startCountdown(result.resendAfterSeconds ?? DEFAULT_RESEND_SECONDS);
        this.focus('#login-code');
      },
      error: (error: unknown) => {
        this.sending.set(false);
        this.handleRequestError(error);
      },
    });
  }

  protected changeNumber(): void {
    this.resetToMobileStep();
  }

  protected resend(): void {
    if (this.resendSeconds() > 0 || this.sending()) {
      return;
    }
    this.sendOtp();
  }

  protected verify(): void {
    if (this.sending()) {
      return;
    }
    this.codeControl.markAsTouched();
    this.codeAttempted.set(true);
    if (this.codeControl.invalid) {
      this.focus('#login-code');
      return;
    }
    if (this.isSignup() && !this.consentControl.value) {
      this.formError.set('Agree to the Terms of Use and Privacy Policy to continue.');
      return;
    }
    this.lockMessage.set(null);
    this.formError.set(null);
    this.sending.set(true);
    const mobile = digitsOnly(this.mobileControl.value);
    const code = digitsOnly(this.codeControl.value);
    const modeAtSubmit = this.authMode();
    const consent = this.isSignup()
      ? { termsVersion: TERMS_VERSION, privacyVersion: PRIVACY_VERSION }
      : undefined;
    this.auth.verifyOtp(mobile, code, modeAtSubmit, consent).subscribe({
      next: (result) => {
        this.sending.set(false);
        this.routeByNext(result, modeAtSubmit);
      },
      error: (error: unknown) => {
        this.sending.set(false);
        this.handleVerifyError(error);
      },
    });
  }

  protected pickClinic(choice: ClinicChoice): void {
    if (this.sending()) {
      return;
    }
    this.sending.set(true);
    this.formError.set(null);
    this.auth.selectClinic(choice.tenantId).subscribe({
      next: (result) => {
        this.sending.set(false);
        this.routeByNext(result, this.authMode());
      },
      error: (error: unknown) => {
        this.sending.set(false);
        this.formError.set(this.messageFor(error) ?? 'Could not open that clinic. Try again.');
      },
    });
  }

  private routeByNext(result: AuthResult, modeAtSubmit: AuthMode): void {
    switch (result.next) {
      case 'SETUP':
        void this.router.navigate(['/setup']);
        return;
      case 'APP':
        if (modeAtSubmit === 'SIGNUP') {
          this.snackBar.open(ALREADY_HAS_CLINIC_MESSAGE, 'Close', { duration: 6000 });
        }
        void this.router.navigate(['/app']);
        return;
      case 'CHOOSE_CLINIC':
        this.clinics.set(result.clinics ?? []);
        this.step.set('chooseClinic');
        return;
      case 'NOT_REGISTERED':
        this.step.set('notRegistered');
        return;
    }
  }

  private handleRequestError(error: unknown): void {
    const problem = error instanceof ApiError ? error.problem : null;
    if (problem?.status === 423) {
      this.lockMessage.set(LOCK_MESSAGE);
      return;
    }
    const fieldMessage = problem?.fieldErrors?.find((item) => item.field === 'mobile')?.message;
    if (fieldMessage) {
      this.mobileControl.setErrors({ server: fieldMessage });
      this.mobileControl.markAsTouched();
      return;
    }
    if (problem?.status === 429) {
      this.startCountdown(problem.retryAfterSeconds ?? DEFAULT_RESEND_SECONDS);
      this.formError.set('A code was already sent. Wait for the countdown before trying again.');
      return;
    }
    this.formError.set(this.messageFor(error) ?? 'We could not send the code. Try again.');
  }

  private handleVerifyError(error: unknown): void {
    const problem = error instanceof ApiError ? error.problem : null;
    if (problem?.status === 423) {
      this.lockMessage.set(LOCK_MESSAGE);
      return;
    }
    if (problem?.status === 403) {
      this.formError.set('Your access to this clinic was removed. Contact your clinic owner.');
      return;
    }
    if (problem?.status === 401) {
      this.codeControl.setErrors({ server: 'That code is wrong or has expired. Request a new one.' });
      this.codeControl.markAsTouched();
      return;
    }
    const fieldMessage = problem?.fieldErrors?.find((item) => item.field === 'code')?.message;
    if (fieldMessage) {
      this.codeControl.setErrors({ server: fieldMessage });
      this.codeControl.markAsTouched();
      return;
    }
    this.formError.set(this.messageFor(error) ?? 'We could not verify the code. Try again.');
  }

  private messageFor(error: unknown): string | null {
    const problem = error instanceof ApiError ? error.problem : null;
    if (problem?.status === 0) {
      return 'We could not reach Sahaj. Check your internet connection and try again.';
    }
    return problem?.detail ?? null;
  }

  private resetToMobileStep(): void {
    this.stopCountdown();
    this.mobileAttempted.set(false);
    this.codeAttempted.set(false);
    this.step.set('mobile');
    this.resendSeconds.set(0);
    this.lockMessage.set(null);
    this.formError.set(null);
    this.codeControl.reset('');
    this.consentControl.reset(false);
  }

  private startCountdown(seconds: number): void {
    this.stopCountdown();
    this.resendSeconds.set(Math.max(0, seconds));
    this.timer = setInterval(() => {
      const next = this.resendSeconds() - 1;
      if (next <= 0) {
        this.resendSeconds.set(0);
        this.stopCountdown();
      } else {
        this.resendSeconds.set(next);
      }
    }, 1000);
  }

  private stopCountdown(): void {
    if (this.timer !== undefined) {
      clearInterval(this.timer);
      this.timer = undefined;
    }
  }

  /** Measures the selected tab and moves the highlight behind it. Layout is only read, never changed. */
  private placeThumb(animated: boolean): void {
    const group = this.seg()?.nativeElement;
    if (!group) {
      return;
    }
    const toggles = group.querySelectorAll<HTMLElement>('mat-button-toggle');
    const target = toggles[this.authMode() === 'SIGNUP' ? 1 : 0];
    if (!target || target.offsetWidth === 0) {
      return;
    }
    const next: TabThumb = { x: target.offsetLeft, width: target.offsetWidth };
    const previous = this.thumb();
    this.thumbSlide.set(animated && previous !== null && !this.reducedMotion.reduced());
    if (!previous || previous.x !== next.x || previous.width !== next.width) {
      this.thumb.set(next);
    }
  }

  /** Keeps the highlight lined up when the window or the text size changes. */
  private watchThumbSize(): void {
    const group = this.seg()?.nativeElement;
    if (!group || typeof ResizeObserver !== 'function') {
      return;
    }
    const observer = new ResizeObserver(() => this.placeThumb(false));
    observer.observe(group);
    this.destroyRef.onDestroy(() => observer.disconnect());
  }

  private focus(selector: string): void {
    setTimeout(() => this.host.nativeElement.querySelector<HTMLElement>(selector)?.focus(), 30);
  }

  protected readonly notRegisteredMessage = NOT_REGISTERED_MESSAGE;
  protected readonly termsVersion = TERMS_VERSION;
}
