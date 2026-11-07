import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { formatDisplayDateTimeOfInstant } from '../../shared/format/ist-date';
import { IconComponent } from '../../shared/icon/icon.component';
import { problemMessage } from '../config/config-format';
import { TeamApi } from '../config/team/team.api';
import {
  COLLEAGUE_TRIES_EMPTY,
  COLLEAGUE_TRIES_ERROR,
  COLLEAGUE_TRIES_TRUNCATED,
  ColleagueTriesEntry,
  ColleagueTriesHistory,
  colleagueEntryText,
  colleagueTriesSheetTitle,
} from './pin.model';

export interface ColleagueTriesSheetData {
  /** The membership id (`TeamMember.id`) whose PIN was tried. */
  id: string;
  name: string;
}

/** The id of the sheet title, for the dialog's `ariaLabelledBy`. */
export const COLLEAGUE_TRIES_TITLE_ID = 'ct-title';

/**
 * History sheet "Wrong PIN tries · <name>" (SAH-38, ADR-014 D9), OWNER only. NEW: the mockup has no
 * such sheet. It lists the last 7 days of wrong tries by colleagues, locks caused by them and right
 * PINs after wrong tries, latest first, each with its time in IST. The names come from the server
 * ("Former staff member" only when it found none) and are shown on screen only. Reading writes
 * nothing. On a failure the sheet shows an error with "Try again", never an empty list.
 */
@Component({
  selector: 'app-colleague-tries-sheet',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, IconComponent],
  styles: `
    .sub {
      font-size: 0.86rem;
    }
    .entries {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
    }
    .entries li {
      display: flex;
      flex-direction: column;
      gap: 2px;
      padding: 10px 0;
      border-bottom: 1px solid var(--line);
    }
    .entries li:last-child {
      border-bottom: 0;
    }
    .entries small {
      color: var(--ink-3);
    }
    .notice button,
    .sheet-f button {
      min-height: 44px;
    }
  `,
  template: `
    <div class="sheet" data-testid="colleague-tries-sheet">
      <div class="sheet-h">
        <div>
          <h2 [id]="titleId">{{ title }}</h2>
        </div>
        <button mat-icon-button type="button" aria-label="Close" data-act="ct-x" (click)="close()">
          <app-icon name="x" [size]="18" />
        </button>
      </div>
      <div class="sheet-b">
        @if (loading()) {
          <p class="muted" role="status" aria-busy="true">Loading</p>
        } @else if (failure(); as message) {
          <div class="notice err" role="alert" data-testid="ct-error">
            <app-icon name="alert" [size]="16" />
            <span>{{ message }}</span>
            <button type="button" mat-stroked-button data-act="ct-retry" (click)="load()">Try again</button>
          </div>
        } @else if (history(); as h) {
          @if (h.entries.length === 0) {
            <p class="muted" data-testid="ct-empty">{{ emptyText }}</p>
          } @else {
            <ul class="entries" data-testid="ct-entries">
              @for (entry of h.entries; track $index) {
                <li data-testid="ct-entry">
                  <span>{{ lineOf(entry) }}</span>
                  <small class="num">{{ timeOf(entry) }}</small>
                </li>
              }
            </ul>
            @if (h.truncated) {
              <p class="muted sub" data-testid="ct-truncated">{{ truncatedText }}</p>
            }
          }
        }
      </div>
      <div class="sheet-f">
        <button mat-stroked-button type="button" data-act="ct-close" (click)="close()">Close</button>
      </div>
    </div>
  `,
})
export class ColleagueTriesSheetComponent {
  private readonly api = inject(TeamApi);
  private readonly dialogRef = inject<MatDialogRef<ColleagueTriesSheetComponent>>(MatDialogRef);
  private readonly destroyRef = inject(DestroyRef);
  private readonly data = inject<ColleagueTriesSheetData>(MAT_DIALOG_DATA);

  protected readonly titleId = COLLEAGUE_TRIES_TITLE_ID;
  protected readonly title = colleagueTriesSheetTitle(this.data.name);
  protected readonly emptyText = COLLEAGUE_TRIES_EMPTY;
  protected readonly truncatedText = COLLEAGUE_TRIES_TRUNCATED;

  protected readonly loading = signal(true);
  protected readonly failure = signal<string | null>(null);
  protected readonly history = signal<ColleagueTriesHistory | null>(null);

  constructor() {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.failure.set(null);
    this.api
      .colleagueTries(this.data.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (history) => {
          this.history.set(history);
          this.loading.set(false);
        },
        error: (error: unknown) => {
          this.history.set(null);
          this.failure.set(problemMessage(error, COLLEAGUE_TRIES_ERROR));
          this.loading.set(false);
        },
      });
  }

  protected lineOf(entry: ColleagueTriesEntry): string {
    return colleagueEntryText(entry);
  }

  protected timeOf(entry: ColleagueTriesEntry): string {
    return `${formatDisplayDateTimeOfInstant(entry.at)} IST`;
  }

  protected close(): void {
    this.dialogRef.close();
  }
}
