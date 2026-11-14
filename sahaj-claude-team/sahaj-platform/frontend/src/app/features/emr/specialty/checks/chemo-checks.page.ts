import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { formatDisplayDateTimeOfInstant } from '../../../../shared/format/ist-date';
import { detailOr, problemSlug } from '../../emr-errors';
import { SpecialtyApi } from '../specialty.api';
import { SecondChecksBadgeStore } from './second-checks-badge.store';
import { ChemoCheckPage, ChemoCheckSummary } from '../specialty.model';
import { decisionLabel, CHECK_LABEL } from '../onco.logic';

/**
 * "Second checks" (SAH-66-3, way a; ADR-023 D6, H7: the page is not drawn in the mockup, a human-decided
 * difference). The named checker signs in on their own device, sees the chemotherapy orders that name
 * them, reads a read-only summary (no dose can be changed) and confirms. The patient's name, UHID, age
 * and sex are shown so the checker knows whose order it is; they stay on screen only. This page grants
 * no access to the EMR record.
 */
@Component({
  selector: 'app-chemo-checks-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule],
  styleUrls: ['../specialty.shared.scss', './chemo-checks.page.scss'],
  template: `
    <div class="page">
      <h2>Second checks</h2>
      <p class="sp-sub">Chemotherapy orders that name you as the independent second checker. Read the order, then confirm.</p>

      @if (error(); as e) {
        <p class="msg crit" role="alert" data-testid="checks-error">{{ e }}</p>
      }

      @if (summary(); as s) {
        <section class="detail" aria-labelledby="chk-h">
          <button type="button" class="link" data-act="back" (click)="close()">← Back to the list</button>
          <h3 id="chk-h">{{ s.patientName }} <span class="sp-sub">{{ s.uhid ?? 'New patient' }} · {{ s.age ?? '—' }} y · {{ s.sex }}</span></h3>
          <p class="sp-sub">Prescribed by {{ s.prescriberName }} · {{ s.protocolLabel }} at {{ s.levelPct }}%{{ s.reductionReason ? ' · ' + s.reductionReason : '' }}</p>
          <p class="sp-sub">Decision: {{ decision(s.decision) }} · BSA used {{ s.bsa.used.toFixed(2) }} m²{{ s.bsa.capped ? ' (capped at 2.0)' : '' }} · {{ s.bsa.source }} · labs drawn {{ drawn(s.labsDrawnAt) }}</p>
          <div class="scroll">
            <table class="tbl" aria-label="Doses">
              <thead><tr><th>Drug</th><th>Calculated</th><th>Rounded</th><th>Final dose</th></tr></thead>
              <tbody>
                @for (d of s.drugs; track d.name) {
                  <tr>
                    <td><b>{{ d.name }}</b></td><td>{{ d.calculatedMg }} mg</td><td>{{ d.roundedMg }} mg</td>
                    <td><b>{{ d.finalMg }} mg</b>@if (d.moreThan5Pct) {<span class="note alert"> More than 5% from calculated</span>}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          <ul class="checks" aria-label="Safety checks">
            @for (c of s.checks; track c.key) {
              <li [class.ok]="c.passed"><span aria-hidden="true">{{ c.passed ? '✓' : '!' }}</span><span class="sr-only">{{ c.passed ? 'Passed:' : 'Not passed:' }}</span> <b>{{ label(c.key) }}</b> <span class="sp-sub">{{ c.detail }}</span></li>
            }
          </ul>
          <p class="sp-sub">Doses cannot be edited here.</p>
          @if (s.secondCheck.state === 'CONFIRMED') {
            <p class="msg good" role="status" data-testid="checks-confirmed">Second check confirmed{{ s.secondCheck.confirmedAt ? ', ' + drawn(s.secondCheck.confirmedAt) : '' }}.</p>
          } @else {
            <button mat-flat-button type="button" data-act="confirm-check" [disabled]="!s.canConfirm || busy()" (click)="confirm()">Confirm second check</button>
            @if (!s.canConfirm) {
              <p class="sp-sub">A safety check is still red, so this order cannot be confirmed yet.</p>
            }
          }
        </section>
      } @else if (loading()) {
        <p class="sp-sub" aria-busy="true">Loading</p>
      } @else {
        <ul class="list">
          @for (i of page()?.items ?? []; track i.orderId) {
            <li>
              <div>
                <b>{{ i.patientName }}</b>
                <span class="sp-sub">{{ i.uhid ?? 'New patient' }} · {{ i.age ?? '—' }} y · {{ i.sex }}</span>
                <span class="sp-sub">Prescribed by {{ i.prescriberName }} · {{ i.protocolLabel }} · named {{ drawn(i.namedAt) }}</span>
              </div>
              <button mat-stroked-button type="button" data-act="open-check" (click)="open(i.orderId)">Open</button>
            </li>
          } @empty {
            <li class="sp-sub" data-testid="checks-empty">Nothing is waiting for your second check.</li>
          }
        </ul>
        @if (more()) {
          <div class="sp-row">
            @if (pageNo() > 0) {
              <button mat-stroked-button type="button" (click)="go(pageNo() - 1)">Earlier</button>
            }
            @if ((pageNo() + 1) * 20 < (page()?.total ?? 0)) {
              <button mat-stroked-button type="button" (click)="go(pageNo() + 1)">More</button>
            }
          </div>
        }
      }
    </div>
  `,
})
export class ChemoChecksPage implements OnInit {
  private readonly api = inject(SpecialtyApi);
  private readonly badge = inject(SecondChecksBadgeStore);
  protected readonly page = signal<ChemoCheckPage | null>(null);
  protected readonly summary = signal<ChemoCheckSummary | null>(null);
  protected readonly loading = signal(true);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly pageNo = signal(0);
  protected readonly more = computed(() => (this.page()?.total ?? 0) > 20);

  ngOnInit(): void {
    this.go(0);
  }

  protected go(n: number): void {
    this.loading.set(true);
    this.error.set(null);
    this.pageNo.set(n);
    this.api.listMyChemoChecks(n).subscribe({
      next: (p) => {
        this.loading.set(false);
        this.page.set(p);
        this.badge.accept(p.total);
      },
      error: (e: unknown) => {
        this.loading.set(false);
        this.error.set(detailOr(e, 'We could not load your second checks. Try again.'));
      },
    });
  }

  protected open(orderId: string, keepError = false): void {
    if (!keepError) {
      this.error.set(null);
    }
    this.api.getChemoCheck(orderId).subscribe({
      next: (s) => this.summary.set(s),
      error: (e: unknown) => this.error.set(detailOr(e, 'We could not open this order. Try again.')),
    });
  }

  protected close(): void {
    this.summary.set(null);
    this.go(this.pageNo());
  }

  protected confirm(): void {
    const s = this.summary();
    if (!s || !s.canConfirm) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.api.confirmChemoCheck(s.orderId, s.fingerprint).subscribe({
      next: (done) => {
        this.busy.set(false);
        this.summary.set(done);
      },
      error: (e: unknown) => {
        this.busy.set(false);
        if (problemSlug(e) === 'chemo-order-changed') {
          this.error.set('The order changed after you read it. Read it again before you confirm.');
          this.open(s.orderId, true);
          return;
        }
        this.error.set(detailOr(e, 'We could not confirm. Try again.'));
      },
    });
  }

  protected decision(key: ChemoCheckSummary['decision']): string {
    return decisionLabel(key);
  }

  protected label(key: string): string {
    return CHECK_LABEL[key] ?? key;
  }

  protected drawn(instant: string | null): string {
    return instant ? formatDisplayDateTimeOfInstant(instant) : '—';
  }
}
