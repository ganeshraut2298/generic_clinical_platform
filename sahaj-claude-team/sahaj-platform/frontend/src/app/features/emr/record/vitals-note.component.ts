import { ChangeDetectionStrategy, Component, OnInit, computed, inject } from '@angular/core';
import { formatDisplayDateTimeOfInstant, formatTimeOfInstant } from '../../../shared/format/ist-date';
import { EmrRecordStore } from './emr-record.store';
import { readingText } from './vitals-prefill.logic';

/**
 * What the nurse recorded for this round, above the Vitals fields (SAH-68-1; mockup #DoctorGeneral "Recorded by
 * Sr. [NURSE NAME] · Nurse"): "Recorded by <name> · Nurse at <time>" while the values are the nurse's, "New
 * vitals recorded. Use them?" when a newer reading exists, and "Last taken by <name>, <date and time>. Doctor's
 * entry for this visit is still needed." when only an earlier reading exists (its numbers are never copied in).
 * Only an IPD round has a nursing chart to read; OPD and Emergency capture none yet, so nothing is drawn there.
 * Names and numbers are for the screen only.
 */
@Component({
  selector: 'app-vitals-note',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host {
      display: block;
    }
    .note {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 8px 12px;
      margin-bottom: 10px;
      padding: 8px 12px;
      border: 1px solid var(--line);
      border-radius: var(--r-sm);
      background: var(--surface-2);
      color: var(--ink-2);
    }
    .note.offer {
      border-color: var(--pri);
      background: var(--pri-soft);
      color: var(--ink);
    }
    button {
      min-height: 44px;
      padding: 0 14px;
      border: 1px solid var(--pri);
      border-radius: var(--r-sm);
      background: var(--pri);
      color: var(--pri-ink);
      font-weight: 600;
      cursor: pointer;
    }
  `,
  template: `
    @if (recordedBy(); as r) {
      <p class="note" data-testid="vitals-recorded">Recorded by {{ r.name }} · Nurse at {{ r.time }}</p>
    }
    @if (offer(); as o) {
      <div class="note offer" role="status" data-testid="vitals-offer">
        <span>New vitals recorded. Use them?</span>
        <span>{{ text(o) }}</span>
        @if (!store.readOnly()) {
          <button type="button" data-act="use-vitals" (click)="store.useVitals(o)">Use them</button>
        }
      </div>
    } @else if (earlier(); as e) {
      <p class="note" data-testid="vitals-earlier">
        Last taken by {{ e.recordedByName }}, {{ when(e.recordedAt) }}. Doctor's entry for this visit is still needed.
      </p>
    }
  `,
})
export class VitalsNoteComponent implements OnInit {
  protected readonly store = inject(EmrRecordStore);

  protected readonly text = readingText;
  protected readonly when = formatDisplayDateTimeOfInstant;

  protected readonly recordedBy = computed(() => {
    const server = this.store.recordSection('vitals')?.prefilledFrom;
    if (server) {
      return { name: server.recordedByName, time: formatTimeOfInstant(server.recordedAt) };
    }
    const used = this.store.vitalsUsedEntry();
    const current = this.store.vitalsRead()?.current;
    return used && current && current.sourceEntryId === used ? { name: current.recordedByName, time: formatTimeOfInstant(current.recordedAt) } : null;
  });
  protected readonly offer = this.store.vitalsOffer;
  protected readonly earlier = computed(() => {
    const v = this.store.vitalsRead();
    return v && !v.current ? v.earlier : null;
  });

  ngOnInit(): void {
    this.store.loadVitals();
  }
}
