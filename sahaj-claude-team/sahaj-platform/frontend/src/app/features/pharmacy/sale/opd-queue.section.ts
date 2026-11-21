import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { IconComponent } from '../../../shared/icon/icon.component';
import { OpdQueuePage } from '../pharmacy-compliance.model';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { PharmacyApi } from '../pharmacy.api';

const STATUS_TEXT: Record<OpdQueuePage['items'][number]['status'], string> = {
  NEW: 'New',
  PICKING: 'Picking',
  PICKED: 'Picked',
  SUBSTITUTE_OK: 'Substitute OK?',
  NEEDS_ACTION: 'Needs action',
  PART_SUPPLIED: 'Part supplied',
};

/**
 * OPD / IPD dispensing (SAH-80-11; P&I mockup 03-hospital-pharmacy-opd-and-ipd.html, tab "Outpatient (OPD)"). The server
 * answers `connected: false` until the OPD port is approved; the tab then says "Not connected" and shows no rows. When
 * it is connected the queue lists the token, the patient, the doctor, the items and the minutes waiting, with the
 * counts New, Picking, Ready and Needs action. Picking, billing and labels (80-12) and the IPD side (80-13 to 80-17) are
 * not on this screen yet; the IPD tab always says "Not connected" here.
 */
@Component({
  selector: 'app-opd-queue',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, IconComponent],
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: 12px;
      min-width: 0;
    }
    .counts {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
    table {
      min-width: 640px;
    }
    .num {
      text-align: right;
      white-space: nowrap;
    }
  `,
  template: `
    <section class="panel surf" aria-labelledby="disp-h" data-testid="dispensing">
      <h2 id="disp-h">OPD / IPD dispensing</h2>
      @if (error(); as text) {
        <div class="notice err" role="alert" data-testid="opd-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
          <button mat-stroked-button type="button" (click)="load()">Try again</button>
        </div>
      } @else if (queue(); as q) {
        @if (opdListed()) {
        <h3>Outpatient (OPD) · {{ q.connected ? q.total : 0 }}</h3>
        <p class="note">Prescriptions from doctor consultations</p>
        }
        @if (!opdListed()) {
          <!-- the OPD queue is not listed for this person -->
        } @else if (!q.connected) {
          <div class="notice warn" role="status" data-testid="dispensing-not-connected">
            <app-icon name="alert" [size]="16" />
            <span>Not connected. The OPD and IPD queues show here when OPD and IPD are connected to Pharmacy.</span>
          </div>
        } @else {
          <div class="counts" data-testid="opd-counts">
            <span class="pill p-blue">New {{ q.counts.new }}</span>
            <span class="pill p-wait">Picking {{ q.counts.picking }}</span>
            <span class="pill p-good">Ready {{ q.counts.ready }}</span>
            <span class="pill p-warn">Needs action {{ q.counts.needsAction }}</span>
            <button mat-stroked-button type="button" data-testid="opd-refresh" (click)="load()">Refresh</button>
          </div>
          @if (q.items.length === 0) {
            <p class="note" data-testid="opd-empty">No prescriptions waiting.</p>
          } @else {
            <div class="tbl-wrap">
              <table data-testid="opd-table">
                <thead>
                  <tr>
                    <th scope="col">Token</th>
                    <th scope="col">Patient</th>
                    <th scope="col">Doctor</th>
                    <th scope="col" class="num">Items</th>
                    <th scope="col" class="num">Waiting</th>
                    <th scope="col">Status</th>
                  </tr>
                </thead>
                <tbody>
                  @for (row of q.items; track row.rxId) {
                    <tr [attr.data-testid]="'opd-row-' + row.token">
                      <td>{{ row.token }}</td>
                      <td>{{ row.patientName }}</td>
                      <td>{{ row.doctorName }}</td>
                      <td class="num">{{ row.itemCount }}</td>
                      <td class="num">{{ row.waitingMinutes }} min</td>
                      <td>
                        {{ statusText(row.status) }}
                        @if (row.status === 'PICKING' && row.pickingBy) {
                          · {{ row.pickingBy.name }}
                        }
                        @if (row.readyMinutes !== null) {
                          · ready {{ row.readyMinutes }} min ago
                        }
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          }
        }
        <h3>Inpatient (IPD)</h3>
        <div class="notice warn" role="status" data-testid="ipd-not-connected">
          <app-icon name="alert" [size]="16" />
          <span>Not connected. The IPD queue shows here when IPD is connected to Pharmacy.</span>
        </div>
      } @else {
        <p class="loading" role="status">Loading</p>
      }
    </section>
  `,
})
export class OpdQueueSection implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly context = inject(PharmacyContextStore);

  protected readonly opdListed = this.context.canOpdQueue;
  protected readonly queue = signal<OpdQueuePage | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly statusText = (s: OpdQueuePage['items'][number]['status']) => STATUS_TEXT[s];

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    const storeId = this.context.workingStoreId();
    this.error.set(null);
    if (!storeId) {
      return;
    }
    if (!this.opdListed()) {
      this.queue.set({ connected: false, refreshAfterSeconds: 60, counts: { new: 0, picking: 0, ready: 0, needsAction: 0 }, items: [], total: 0, page: 0, size: 25 });
      return;
    }
    this.api.opdQueue(storeId).subscribe({
      next: (page) => this.queue.set(page),
      error: () => {
        this.queue.set(null);
        this.error.set('Prescriptions could not be loaded. Try again.');
      },
    });
  }
}
