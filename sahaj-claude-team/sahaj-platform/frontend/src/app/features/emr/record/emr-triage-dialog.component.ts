import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { EmrTriageLevel, EmrTriageScale } from '../emr-finish.model';
import { EmrRecordStore } from './emr-record.store';

/**
 * Triage level for the Emergency visit type (SAH-67-4): the clinic's triage levels from Emergency
 * setup, or the default three colours when the clinic has none. A level is needed before the visit
 * can be an Emergency visit. Each level shows its name as text, with its colour as a second signal.
 */
@Component({
  selector: 'app-emr-triage-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatDialogModule],
  styles: `
    fieldset {
      border: 0;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    .opt {
      display: flex;
      align-items: center;
      gap: 10px;
      min-height: 44px;
      padding: 6px 12px;
      border: 1px solid var(--line);
      border-radius: var(--r-sm);
      cursor: pointer;
    }
    .opt input {
      width: 20px;
      height: 20px;
    }
    .opt.on {
      border-color: var(--pri);
      background: var(--pri-soft);
    }
    .dot {
      width: 12px;
      height: 12px;
      border-radius: 50%;
      background: var(--ink-3);
    }
    .dot.crit {
      background: var(--crit);
    }
    .dot.warn {
      background: var(--warn);
    }
    .dot.good {
      background: var(--good);
    }
    .txt {
      display: flex;
      flex-direction: column;
    }
    button {
      min-height: 44px;
    }
  `,
  template: `
    <h2 mat-dialog-title id="triage-title">Triage level</h2>
    <mat-dialog-content>
      @if (error(); as message) {
        <p class="err-text" role="alert">
          {{ message }}
          <button type="button" class="linkbtn" data-act="retry-levels" (click)="load()">Try again</button>
        </p>
      } @else if (scale(); as s) {
        <fieldset>
          <legend class="sr-only">Triage level</legend>
          @for (l of s.levels; track l.level) {
            <label class="opt" [class.on]="chosen() === l.level">
              <input type="radio" name="triage" [value]="l.level" [checked]="chosen() === l.level" [attr.data-level]="l.level" (change)="chosen.set(l.level)" />
              <span class="dot" [class]="l.tone" aria-hidden="true"></span>
              <span class="txt">
                <b>{{ l.name }}</b>
                <span class="faint t-xs">{{ l.description }}</span>
              </span>
            </label>
          }
        </fieldset>
        <p class="faint t-xs">The visit moves to the Emergency group at the top of the Queue.</p>
      } @else {
        <p class="faint" aria-busy="true">Loading the triage levels</p>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button type="button" mat-button data-act="triage-cancel" (click)="ref.close(null)">Cancel</button>
      <button type="button" mat-flat-button data-act="triage-save" [disabled]="chosen() === null" (click)="ref.close(chosen())">Mark as Emergency</button>
    </mat-dialog-actions>
  `,
})
export class EmrTriageDialogComponent implements OnInit {
  protected readonly ref = inject<MatDialogRef<EmrTriageDialogComponent, number | null>>(MatDialogRef);
  private readonly store = inject(EmrRecordStore);

  protected readonly scale = signal<EmrTriageScale | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly chosen = signal<number | null>(null);
  protected readonly levels = computed<EmrTriageLevel[]>(() => this.scale()?.levels ?? []);

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.error.set(null);
    this.store.triageLevels().subscribe((scale) => {
      if (scale) {
        this.scale.set(scale);
      } else {
        this.error.set('We could not load the triage levels.');
      }
    });
  }
}
