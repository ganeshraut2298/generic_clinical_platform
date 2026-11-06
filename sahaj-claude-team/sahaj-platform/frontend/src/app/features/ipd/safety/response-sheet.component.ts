import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Signal,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { ApiError } from '../../../core/http/problem-detail';
import { IconComponent } from '../../../shared/icon/icon.component';
import { problemMessage } from '../../config/config-format';
import { SafetyApi } from './safety.api';
import {
  ACTION_MISSING,
  EscalationActionResult,
  EscalationResponseActionOption,
  EscalationRow,
  KEY_ERROR,
  NOT_ADMITTED,
  PLAN_TOO_LONG,
  PLAN_MAX,
  RESPONSE_ERROR,
  ResponseErrors,
  planCounterText,
  planLength,
  responseSubtitle,
  responseTitle,
  toResponseInput,
  validateResponse,
} from './safety.model';

export interface ResponseSheetData {
  /** The row as the doctor saw it when the sheet opened: its `version` is sent. */
  row: EscalationRow;
  actions: EscalationResponseActionOption[];
  /** The same escalation on the board, reloaded behind the sheet after a conflict. */
  live: Signal<EscalationRow | null>;
  /** Asks the board to reload (called on a 409 or 404). */
  onConflict: () => void;
}

const NOTE_PLACEHOLDER = 'For example: oxygen increased to 6 L, repeat vitals in 30 minutes';

/**
 * Response sheet (SAH-33), mockup `sfresp` and `sfrespok`: "Response · <patient>", the select
 * "What was done" (first option preselected), the text area "Plan and next vitals" and the footer
 * Cancel and Close escalation.
 *
 * - The empty plan is refused here with the mockup's message and again by the server; the sheet stays
 *   open with the typed text on every failure.
 * - Close escalation, Cancel and the x button are off while the request runs (double-submit guard),
 *   and Esc and the backdrop do nothing then: closing would cancel the wait for an answer the server
 *   may already have committed.
 * - With a typed plan, Esc and the backdrop do nothing; Cancel and x ask "Discard the plan you typed?"
 *   first. The plan has a counter and the limit of 1,000 characters (code points, as the server counts).
 * - The `version` sent is the one of the row when the sheet opened. After a 409 the board reloads
 *   behind the sheet; the sheet then shows the new score in its subtitle, so the doctor reviews it and
 *   taps again with the new version. A response is never recorded against a score not seen.
 * - The plan is clinical text: it is only sent to the server; it is never logged or stored.
 */
@Component({
  selector: 'app-response-sheet',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, MatButtonModule, IconComponent],
  styleUrl: './response-sheet.component.scss',
  templateUrl: './response-sheet.component.html',
})
export class ResponseSheetComponent {
  private readonly api = inject(SafetyApi);
  private readonly dialogRef = inject<MatDialogRef<ResponseSheetComponent, EscalationActionResult>>(MatDialogRef);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly data = inject<ResponseSheetData>(MAT_DIALOG_DATA);

  protected readonly actions = this.data.actions;
  protected readonly placeholder = NOTE_PLACEHOLDER;

  protected readonly current = signal<EscalationRow>(this.data.row);
  protected readonly running = signal(false);
  protected readonly errors = signal<ResponseErrors>({});
  protected readonly failure = signal<string | null>(null);
  /** A 409 or 404 happened: the next board reload decides what the sheet shows. */
  private readonly conflicted = signal(false);
  /** The escalation can take no response any more (closed, or the patient left). */
  protected readonly locked = signal(false);

  protected readonly title = computed(() => responseTitle(this.current()));
  protected readonly subtitle = computed(() => responseSubtitle(this.current()));

  protected readonly form = new FormGroup({
    action: new FormControl(this.data.actions[0]?.key ?? '', { nonNullable: true }),
    note: new FormControl('', { nonNullable: true }),
  });

  /** What is typed in the plan, as a signal: the counter and the discard check follow it. */
  private readonly noteText = toSignal(this.form.controls.note.valueChanges, { initialValue: '' });
  /** A plan was typed: Esc and a click outside must not throw it away. */
  protected readonly dirty = computed(() => this.noteText().trim() !== '');
  protected readonly counter = computed(() => planCounterText(planLength(this.noteText())));
  protected readonly overLimit = computed(() => planLength(this.noteText()) > PLAN_MAX);
  protected readonly tooLong = PLAN_TOO_LONG;
  /** Cancel or x was pressed with a typed plan: ask before discarding it. */
  private readonly asking = signal(false);
  protected readonly confirming = computed(() => this.asking() && this.dirty() && !this.running());

  constructor() {
    // Esc and the backdrop close the sheet only when nothing would be lost and nothing is running.
    effect(() => {
      this.dialogRef.disableClose = this.running() || this.dirty();
    });
    effect(() => {
      const live = this.data.live();
      if (!untracked(this.conflicted)) {
        return;
      }
      const shown = untracked(this.current);
      if (live === null) {
        this.locked.set(true);
      } else if (live.version !== shown.version || live.status !== shown.status) {
        this.current.set(live);
        this.locked.set(!live.canRespond);
      }
    });
  }

  protected edited(field: 'action' | 'note'): void {
    if (this.errors()[field]) {
      const rest = { ...this.errors() };
      delete rest[field];
      this.errors.set(rest);
    }
  }

  protected submit(): void {
    if (this.running() || this.locked()) {
      return;
    }
    const draft = this.form.getRawValue();
    const errors = validateResponse(draft, this.actions);
    this.errors.set(errors);
    this.failure.set(null);
    if (errors.action) {
      this.focus('rs-action');
      return;
    }
    if (errors.note) {
      this.focus('rs-note');
      return;
    }
    this.running.set(true);
    this.asking.set(false);
    this.api
      .respond(this.current().id, toResponseInput(this.current().version, draft))
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          this.running.set(false);
          this.dialogRef.close(result);
        },
        error: (error: unknown) => {
          this.running.set(false);
          this.failed(error);
        },
      });
  }

  private failed(error: unknown): void {
    if (!(error instanceof ApiError)) {
      this.failure.set(RESPONSE_ERROR);
      return;
    }
    if (error.status === 400) {
      this.badRequest(error);
      return;
    }
    if (error.status === 409) {
      this.conflicted.set(true);
      this.data.onConflict();
    } else if (error.status === 404) {
      this.conflicted.set(true);
      this.locked.set(true);
      this.data.onConflict();
      this.failure.set(problemMessage(error, NOT_ADMITTED));
      return;
    }
    this.failure.set(problemMessage(error, RESPONSE_ERROR));
  }

  private badRequest(error: ApiError): void {
    const shown: ResponseErrors = {};
    let other: string | null = null;
    for (const item of error.fieldErrors) {
      if (item.field === 'action') {
        shown.action ??= ACTION_MISSING;
      } else if (item.field === 'note') {
        shown.note ??= item.message;
      } else if (item.field === 'version') {
        other ??= KEY_ERROR;
      } else {
        other ??= item.message;
      }
    }
    this.errors.set(shown);
    if (shown.action) {
      this.focus('rs-action');
    } else if (shown.note) {
      this.focus('rs-note');
    }
    if (other !== null || (!shown.action && !shown.note)) {
      this.failure.set(other ?? problemMessage(error, RESPONSE_ERROR));
    }
  }

  /** For a control that is drawn only after the next change detection. */
  private focusLater(id: string): void {
    setTimeout(() => this.focus(id), 0);
  }

  private focus(id: string): void {
    this.host.nativeElement.querySelector<HTMLElement>(`#${id}`)?.focus();
  }

  /** Cancel and the x button: off while the request runs; asks first when a plan was typed. */
  protected cancel(): void {
    if (this.running()) {
      return;
    }
    if (this.dirty()) {
      this.asking.set(true);
      this.focusLater('rs-discard');
      return;
    }
    this.dialogRef.close();
  }

  protected keepEditing(): void {
    this.asking.set(false);
    this.focusLater('rs-note');
  }

  protected discard(): void {
    if (!this.running()) {
      this.dialogRef.close();
    }
  }
}
