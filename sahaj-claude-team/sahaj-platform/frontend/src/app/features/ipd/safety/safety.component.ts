import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Observable, Subscription } from 'rxjs';
import { ApiError } from '../../../core/http/problem-detail';
import { IconComponent } from '../../../shared/icon/icon.component';
import { problemMessage } from '../../config/config-format';
import { PharmacistPanelComponent } from '../pharmacist/pharmacist-panel.component';
import { PharmacistTileComponent } from '../pharmacist/pharmacist-tile.component';
import { ResponseSheetComponent, ResponseSheetData } from './response-sheet.component';
import { SafetyApi } from './safety.api';
import { startVisiblePoll } from './visible-poll';
import {
  ACTION_ERROR,
  AUDIT_TITLE,
  ClinicalEvent,
  ClinicalEventPage,
  EVENTS_ERROR,
  EscalationActionResult,
  EscalationRow,
  FIRST_PAGE,
  LOAD_ERROR,
  NEXT_PAGE,
  NOT_ADMITTED,
  NO_ESCALATIONS,
  NO_EVENTS,
  PANEL_SUBTITLE,
  PANEL_TITLE,
  SHOW_EARLIER,
  SafetyBoard,
  SafetyCounts,
  YOUR_PATIENTS_TEXT,
  ackToast,
  auditSubtitle,
  callToast,
  closedToast,
  detailLine,
  eventLine,
  eventTime,
  kindIcon,
  mergeEvents,
  noticeBody,
  noticeHead,
  openTileText,
  raisedLine,
  riskWord,
  staleBoardText,
  statusPill,
} from './safety.model';

/** Same interval as the Emergency queue (DEC-015, OQ-33-10). */
export const SAFETY_REFRESH_MS = 60_000;

/** The heading of the panel "Early warning escalations": focus goes here after a close. */
export const ESCALATIONS_HEADING_ID = 'sf-esc-h';

/**
 * The Safety tab (SAH-33), mockup `ipSafety`: the red notice, the tiles, the panel "Early warning
 * escalations" and the panel "Clinical audit trail". Buttons follow the row flags the server sends
 * (`canAcknowledge`, `canRespond`, `canCallSenior`); the server checks the role again on every call.
 *
 * - Every "ago" and the overdue state come from the server (`asOf`, minute counts); the device clock
 *   is never read.
 * - The board reloads every 60 seconds and after each action; a failed reload keeps the last data and
 *   shows "Could not refresh. Showing data from <time>.". Loading shows only on the first load.
 * - One action runs at a time: every row button is off meanwhile (double-submit guard). A row is
 *   never shown as changed until the server has answered.
 * - A 409 (`escalation-state`, `escalation-changed`) or 404 shows the server's message as a notice and
 *   reloads the board. A replayed 200 is treated as a success.
 * - Names and the plan are on screen only: nothing is logged or stored.
 */
@Component({
  selector: 'app-safety',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, IconComponent, PharmacistTileComponent, PharmacistPanelComponent],
  styleUrl: './safety.component.scss',
  templateUrl: './safety.component.html',
})
export class SafetyComponent implements OnInit {
  private readonly api = inject(SafetyApi);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly destroyRef = inject(DestroyRef);
  private readonly doc = inject(DOCUMENT);

  /** Counts of every board answer: the tab number and the banner of the page follow them. */
  readonly counts = output<SafetyCounts>();
  /**
   * SAH-31: the owner's Safety tab also has the tile "Pharmacist checks" and the panel "Pharmacist
   * check" (mockup: owner only). The page provides the `PharmacistStore` they read.
   */
  readonly showPharmacist = input(false);

  protected readonly board = signal<SafetyBoard | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  /** The last reload failed: the old board stays and a small warning shows. */
  protected readonly refreshFailed = signal(false);
  /** One action runs at a time; every row button is off meanwhile. */
  protected readonly busy = signal(false);
  /** The message of a refused action (409, 404, 403 ...), kept until the next action or Dismiss. */
  protected readonly actionNotice = signal<string | null>(null);

  protected readonly events = signal<ClinicalEvent[]>([]);
  protected readonly nextCursor = signal<string | null>(null);
  protected readonly eventsAsOf = signal<string | null>(null);
  protected readonly eventsLoading = signal(false);
  protected readonly eventsError = signal<string | null>(null);

  protected readonly panelTitle = PANEL_TITLE;
  protected readonly panelSubtitle = PANEL_SUBTITLE;
  protected readonly noEscalations = NO_ESCALATIONS;
  protected readonly auditTitle = AUDIT_TITLE;
  protected readonly noEvents = NO_EVENTS;
  protected readonly showEarlier = SHOW_EARLIER;
  protected readonly yourPatientsText = YOUR_PATIENTS_TEXT;
  protected readonly pill = statusPill;
  protected readonly raised = raisedLine;
  protected readonly detail = detailLine;
  protected readonly risk = riskWord;
  protected readonly head = noticeHead;
  protected readonly body = noticeBody;
  protected readonly tileText = openTileText;
  protected readonly subtitleOf = auditSubtitle;
  protected readonly line = eventLine;
  protected readonly icon = kindIcon;

  protected readonly staleText = computed(() => staleBoardText(this.board()?.asOf));
  protected readonly time = (event: ClinicalEvent): string =>
    eventTime(event.at, this.eventsAsOf() ?? event.at);

  private boardSub: Subscription | null = null;
  private eventsSub: Subscription | null = null;

  ngOnInit(): void {
    this.load();
    startVisiblePoll(this.doc, SAFETY_REFRESH_MS, () => this.refresh(), this.destroyRef);
    this.destroyRef.onDestroy(() => {
      this.boardSub?.unsubscribe();
      this.eventsSub?.unsubscribe();
    });
  }

  /** First load, and "Try again": shows Loading. */
  protected load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.fetchBoard(
      () => this.loading.set(false),
      (error) => {
        this.loading.set(false);
        this.loadError.set(problemMessage(error, LOAD_ERROR));
      },
    );
    this.fetchEvents(false);
  }

  /** The 60-second reload: no Loading text; a failure keeps what is on screen. */
  protected refresh(): void {
    if (this.loading()) {
      return;
    }
    this.fetchBoard(
      () => undefined,
      () => this.refreshFailed.set(true),
    );
    this.fetchEvents(true);
  }

  private fetchBoard(done: () => void, failed: (error: unknown) => void): void {
    this.boardSub?.unsubscribe();
    this.boardSub = this.api.board().subscribe({
      next: (board) => {
        this.refreshFailed.set(false);
        this.loadError.set(null);
        this.board.set(board);
        this.counts.emit(board.counts);
        done();
      },
      error: failed,
    });
  }

  /** First page (14). While older entries are showing, the new entries are merged on top. */
  private fetchEvents(quiet: boolean): void {
    this.eventsSub?.unsubscribe();
    if (!quiet) {
      this.eventsError.set(null);
    }
    this.eventsSub = this.api.events(FIRST_PAGE).subscribe({
      next: (page) => {
        this.eventsAsOf.set(page.asOf);
        const merged = mergeEvents({ items: this.events(), nextCursor: this.nextCursor() }, page);
        this.events.set(merged.items);
        this.nextCursor.set(merged.nextCursor);
        this.eventsError.set(null);
      },
      error: (error: unknown) => {
        if (!quiet) {
          this.eventsError.set(problemMessage(error, EVENTS_ERROR));
        }
      },
    });
  }

  /** "Show earlier entries": 50 more, from the cursor of the last page. */
  protected loadMore(): void {
    const cursor = this.nextCursor();
    if (cursor === null || this.eventsLoading()) {
      return;
    }
    this.eventsLoading.set(true);
    this.eventsError.set(null);
    this.api
      .events(NEXT_PAGE, cursor)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (page: ClinicalEventPage) => {
          this.eventsLoading.set(false);
          const known = new Set(this.events().map((e) => e.id));
          this.events.set([...this.events(), ...page.items.filter((e) => !known.has(e.id))]);
          this.nextCursor.set(page.nextCursor);
        },
        error: (error: unknown) => {
          this.eventsLoading.set(false);
          this.eventsError.set(problemMessage(error, EVENTS_ERROR));
        },
      });
  }

  /**
   * The button that opened the Response sheet is gone once the escalation is closed, so focus goes
   * to the panel heading (tabindex -1). One tick later: the dialog gives focus back to its opener
   * while it closes, and that must not win.
   */
  private focusHeading(): void {
    setTimeout(() => this.doc.getElementById(ESCALATIONS_HEADING_ID)?.focus(), 0);
  }

  private reloadAll(): void {
    this.fetchBoard(
      () => undefined,
      () => this.refreshFailed.set(true),
    );
    this.fetchEvents(true);
  }

  protected toast(message: string): void {
    this.snackBar.open(message, 'Close', { duration: 6000 });
  }

  protected dismissNotice(): void {
    this.actionNotice.set(null);
  }

  protected acknowledge(row: EscalationRow): void {
    if (this.busy() || !row.canAcknowledge) {
      return;
    }
    this.run(this.api.acknowledge(row.id, row.version), (r) => ackToast(r.escalation.patientName));
  }

  protected callSenior(row: EscalationRow): void {
    if (this.busy() || !row.canCallSenior) {
      return;
    }
    this.run(
      this.api.callSenior(row.id, { version: row.version, seniorCalls: row.seniorCalls }),
      (r) => callToast(r.escalation.patientName),
    );
  }

  private run(
    call: Observable<EscalationActionResult>,
    toast: (result: EscalationActionResult) => string,
  ): void {
    this.busy.set(true);
    this.actionNotice.set(null);
    call.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (result) => {
        this.busy.set(false);
        this.toast(toast(result));
        this.reloadAll();
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.failAction(error);
      },
    });
  }

  /** A state change on the server: say what happened and reload what is really on the board. */
  private failAction(error: unknown): void {
    if (error instanceof ApiError && (error.status === 409 || error.status === 404)) {
      this.actionNotice.set(
        problemMessage(error, error.status === 404 ? NOT_ADMITTED : ACTION_ERROR),
      );
      this.reloadAll();
      return;
    }
    this.actionNotice.set(problemMessage(error, ACTION_ERROR));
  }

  /** Record response: the sheet keeps the typed plan on every failure; the board reloads behind it on a 409. */
  protected openResponse(row: EscalationRow): void {
    const board = this.board();
    if (this.busy() || !board || !row.canRespond) {
      return;
    }
    const live = computed(() => this.board()?.escalations.find((e) => e.id === row.id) ?? null);
    this.dialog
      .open<ResponseSheetComponent, ResponseSheetData, EscalationActionResult | undefined>(
        ResponseSheetComponent,
        {
          panelClass: 'sheet-panel',
          width: '640px',
          maxWidth: '100vw',
          data: {
            row,
            actions: board.responseActions,
            live,
            onConflict: () => this.reloadAll(),
          },
        },
      )
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((result) => {
        if (result) {
          this.actionNotice.set(null);
          this.toast(closedToast(result.escalation.patientName));
          this.reloadAll();
          this.focusHeading();
        }
      });
  }
}
