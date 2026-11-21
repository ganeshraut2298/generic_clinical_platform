import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal, viewChild } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { newClientKey } from '../../../shared/client-key';
import { IconComponent } from '../../../shared/icon/icon.component';
import { pinProblem } from '../indent/indent.util';
import { ReasonChipsComponent } from '../indent/reason-chips.component';
import { SecondSignerComponent } from '../indent/second-signer.component';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { problemSlug, saveErrorMessage } from '../pharmacy-display.util';
import { ActiveReason, SecondSignerCandidate, StockBatchRow } from '../pharmacy-inventory.model';
import { DAMAGE_STATUS_LABELS, DamageAfterApproval } from '../pharmacy-stockuse.model';
import { PharmacyApi } from '../pharmacy.api';
import { BatchPickComponent } from './batch-pick.component';

interface CardState {
  row: StockBatchRow | null;
  qty: string;
  reasonKey: string | null;
  note: string;
  qtyError: string | null;
  reasonError: string | null;
  signerError: string | null;
  pinError: string | null;
  formError: string | null;
  saving: boolean;
}

const EMPTY: CardState = {
  row: null,
  qty: '',
  reasonKey: null,
  note: '',
  qtyError: null,
  reasonError: null,
  signerError: null,
  pinError: null,
  formError: null,
  saving: false,
};

/**
 * "Return to Central Store" and "Report damaged / expired" (stories 79-14, mockup 11 tab "Return / damaged"). A return
 * moves Available stock to Pending return at this store and waits for the Central Store; nothing enters the Central
 * Store until it accepts (BR-29). A damage report moves the units to Quarantine at once, and the HOD decides the next
 * step (write off at the store, or send to the Central Store; at the Central Store only write-off). "Add photo" is not
 * built (OQ-79-25). A Schedule X batch needs a pharmacist and a second signature.
 */
@Component({
  selector: 'app-returns-damage',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    IconComponent,
    BatchPickComponent,
    ReasonChipsComponent,
    SecondSignerComponent,
  ],
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: 12px;
      min-width: 0;
    }
    .cards {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
      gap: 12px;
    }
    .card {
      display: flex;
      flex-direction: column;
      gap: 10px;
    }
    .err {
      color: var(--crit);
      font-size: 0.8rem;
    }
    fieldset {
      border: 0;
      padding: 0;
      display: flex;
      gap: 12px;
      flex-wrap: wrap;
    }
    fieldset label {
      display: flex;
      gap: 6px;
      align-items: center;
      min-height: 44px;
    }
  `,
  template: `
    @if (notice(); as text) {
      <div class="notice good" role="status" data-testid="rd-note">
        <app-icon name="check" [size]="16" />
        <span>{{ text }}</span>
      </div>
    }
    <div class="cards">
      @if (canReturnToCs()) {
        <section class="panel surf card" aria-labelledby="ret-h" data-testid="return-card">
          <h2 id="ret-h">Return to Central Store</h2>
          <p class="note">Excess, near-expiry or no longer needed. No indent needed.</p>
          <app-batch-pick #retPick [storeId]="storeId()" (picked)="pickReturn($event)" />
          @if (ret().row; as row) {
            <p data-testid="return-batch">
              <b>{{ row.product.name }} · {{ row.batch.batchNo }}</b>
            </p>
            <mat-form-field appearance="outline" subscriptSizing="dynamic">
              <mat-label>Quantity</mat-label>
              <input
                matInput
                inputmode="numeric"
                data-testid="return-qty"
                [value]="ret().qty"
                (input)="setRet({ qty: $any($event.target).value, qtyError: null })"
              />
            </mat-form-field>
            @if (ret().qtyError; as text) {
              <p class="err" role="alert" data-testid="return-qty-error">{{ text }}</p>
            }
            <span class="field-label" id="ret-reason">Reason</span>
            <app-reason-chips
              [reasons]="returnReasons()"
              [value]="ret().reasonKey"
              label="Reason for the return"
              testIdPrefix="return-reason-"
              (chosen)="setRet({ reasonKey: $event.reasonKey, reasonError: null })"
            />
            @if (ret().reasonError; as text) {
              <p class="err" role="alert" data-testid="return-reason-error">{{ text }}</p>
            }
            @if (needsNote(returnReasons(), ret().reasonKey)) {
              <mat-form-field appearance="outline" subscriptSizing="dynamic">
                <mat-label>Note</mat-label>
                <textarea matInput rows="2" maxlength="300" data-testid="return-note" [value]="ret().note" (input)="setRet({ note: $any($event.target).value })"></textarea>
              </mat-form-field>
            }
            @if (row.product.schedule === 'X') {
              <app-second-signer
                [signers]="signers()"
                [loaded]="signersLoaded()"
                [signerError]="ret().signerError"
                [pinError]="ret().pinError"
                [(signerId)]="retSigner"
                [(pin)]="retPin"
              />
            }
          }
          @if (ret().formError; as text) {
            <div class="notice err" role="alert" data-testid="return-error">
              <app-icon name="alert" [size]="16" />
              <span>{{ text }}</span>
            </div>
          }
          <div>
            <button mat-flat-button type="button" data-testid="return-send" [disabled]="ret().saving || !ret().row" (click)="sendReturn()">
              Send to Central Store
            </button>
          </div>
        </section>
      }

      <section class="panel surf card" aria-labelledby="dmg-h" data-testid="damage-card">
        <h2 id="dmg-h">Report damaged / expired</h2>
        <p class="note">Moved to quarantine at once so it cannot be used. HOD approves the next step.</p>
        <app-batch-pick #dmgPick [storeId]="storeId()" [states]="['AVAILABLE', 'QUARANTINE']" [allowExpired]="true" (picked)="pickDamage($event)" />
        @if (dmg().row; as row) {
          <p data-testid="damage-batch">
            <b>{{ row.product.name }} · {{ row.batch.batchNo }}</b>
          </p>
          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Quantity</mat-label>
            <input
              matInput
              inputmode="numeric"
              data-testid="damage-qty"
              [value]="dmg().qty"
              (input)="setDmg({ qty: $any($event.target).value, qtyError: null })"
            />
          </mat-form-field>
          @if (dmg().qtyError; as text) {
            <p class="err" role="alert" data-testid="damage-qty-error">{{ text }}</p>
          }
          <span class="field-label">Reason</span>
          <app-reason-chips
            [reasons]="damageReasons()"
            [value]="dmg().reasonKey"
            label="Reason for the damage report"
            testIdPrefix="damage-reason-"
            (chosen)="setDmg({ reasonKey: $event.reasonKey, reasonError: null })"
          />
          @if (dmg().reasonError; as text) {
            <p class="err" role="alert" data-testid="damage-reason-error">{{ text }}</p>
          }
          @if (needsNote(damageReasons(), dmg().reasonKey)) {
            <mat-form-field appearance="outline" subscriptSizing="dynamic">
              <mat-label>Note</mat-label>
              <textarea matInput rows="2" maxlength="300" data-testid="damage-note" [value]="dmg().note" (input)="setDmg({ note: $any($event.target).value })"></textarea>
            </mat-form-field>
          }
          <fieldset>
            <legend>After approval</legend>
            <label>
              <input type="radio" name="after" data-testid="after-write-off" [checked]="after() === 'WRITE_OFF'" (change)="after.set('WRITE_OFF')" />
              Write off at store
            </label>
            @if (!isCentral()) {
              <label>
                <input type="radio" name="after" data-testid="after-send" [checked]="after() === 'SEND_TO_CS'" (change)="after.set('SEND_TO_CS')" />
                Send to Central Store
              </label>
            }
          </fieldset>
          @if (row.product.schedule === 'X') {
            <app-second-signer
              [signers]="signers()"
              [loaded]="signersLoaded()"
              [signerError]="dmg().signerError"
              [pinError]="dmg().pinError"
              [(signerId)]="dmgSigner"
              [(pin)]="dmgPin"
            />
          }
        }
        @if (dmg().formError; as text) {
          <div class="notice err" role="alert" data-testid="damage-error">
            <app-icon name="alert" [size]="16" />
            <span>{{ text }}</span>
          </div>
        }
        <div>
          <button mat-flat-button type="button" data-testid="damage-submit" [disabled]="dmg().saving || !dmg().row" (click)="submitDamage()">
            Submit for HOD approval
          </button>
        </div>
      </section>
    </div>
    <p class="note">Stock is added to Central Store only after Central Store accepts the return. Every step is recorded with user, store, time and reason.</p>
  `,
})
export class ReturnsDamageSection implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly context = inject(PharmacyContextStore);

  private readonly retPicker = viewChild<BatchPickComponent>('retPick');
  private readonly dmgPicker = viewChild<BatchPickComponent>('dmgPick');

  protected readonly ret = signal<CardState>({ ...EMPTY });
  protected readonly dmg = signal<CardState>({ ...EMPTY });
  protected readonly after = signal<DamageAfterApproval>('WRITE_OFF');
  protected readonly returnReasons = signal<ActiveReason[]>([]);
  protected readonly damageReasons = signal<ActiveReason[]>([]);
  protected readonly signers = signal<SecondSignerCandidate[]>([]);
  protected readonly signersLoaded = signal(false);
  protected readonly retSigner = signal('');
  protected readonly retPin = signal('');
  protected readonly dmgSigner = signal('');
  protected readonly dmgPin = signal('');
  protected readonly notice = signal<string | null>(null);

  private retKey = newClientKey();
  private dmgKey = newClientKey();

  protected readonly storeId = computed(() => this.context.workingStoreId() ?? '');
  protected readonly isCentral = this.context.isCentralStore;
  /** A return to the Central Store is never raised at the Central Store (contract: store-returns). */
  protected readonly canReturnToCs = computed(() => this.context.canReturnDamage() && !this.context.isCentralStore());

  ngOnInit(): void {
    this.api.activeReasons(['store-return', 'damage']).subscribe({
      next: (answer) => {
        this.returnReasons.set(answer.lists.find((l) => l.listKey === 'store-return')?.reasons ?? []);
        this.damageReasons.set(answer.lists.find((l) => l.listKey === 'damage')?.reasons ?? []);
      },
      error: () => undefined,
    });
    const storeId = this.context.workingStoreId();
    if (storeId) {
      this.api.secondSigners(storeId).subscribe({
        next: (people) => {
          this.signers.set(people);
          this.signersLoaded.set(true);
        },
        error: () => this.signersLoaded.set(true),
      });
    }
    if (this.isCentral()) {
      this.after.set('WRITE_OFF');
    }
  }

  protected needsNote(list: ActiveReason[], key: string | null): boolean {
    return list.find((r) => r.reasonKey === key)?.needsNote === true;
  }

  protected setRet(change: Partial<CardState>): void {
    this.ret.update((s) => ({ ...s, ...change }));
  }

  protected setDmg(change: Partial<CardState>): void {
    this.dmg.update((s) => ({ ...s, ...change }));
  }

  protected pickReturn(row: StockBatchRow): void {
    this.ret.set({ ...EMPTY, row });
  }

  protected pickDamage(row: StockBatchRow): void {
    this.dmg.set({ ...EMPTY, row });
  }

  private qtyProblem(value: string, max: number | null): string | null {
    const text = value.trim();
    if (!/^\d{1,6}$/.test(text) || Number(text) < 1) {
      return 'Enter a whole number from 1';
    }
    if (max !== null && Number(text) > max) {
      return `Enter 1 to ${max}`;
    }
    return null;
  }

  protected sendReturn(): void {
    const s = this.ret();
    const row = s.row;
    if (s.saving || !row) {
      return;
    }
    const qtyError = this.qtyProblem(s.qty, row.qty);
    const reason = this.returnReasons().find((r) => r.reasonKey === s.reasonKey);
    const reasonError = reason ? null : 'Choose a reason';
    const x = row.product.schedule === 'X';
    const pinError = x ? pinProblem(this.retPin()) : null;
    const signerError = x && !this.retSigner() ? 'Choose the second signer' : null;
    const noteError = reason?.needsNote && s.note.trim().length < 4 ? 'Write a note of at least 4 characters' : null;
    this.setRet({ qtyError, reasonError: reasonError ?? noteError, pinError, signerError, formError: null });
    if (qtyError || reasonError || noteError || pinError || signerError) {
      return;
    }
    this.setRet({ saving: true });
    this.api
      .createStoreReturn(this.storeId(), {
        clientKey: this.retKey,
        batchId: row.batch.batchId,
        quantity: Number(s.qty.trim()),
        reasonKey: s.reasonKey ?? '',
        note: s.note.trim() === '' ? null : s.note.trim(),
        secondSigner: x ? { membershipId: this.retSigner(), pin: this.retPin() } : null,
      })
      .subscribe({
        next: (done) => {
          this.retPin.set('');
          this.retKey = newClientKey();
          this.ret.set({ ...EMPTY });
          this.retPicker()?.reset();
          this.notice.set(`${done.number} sent. Waiting for Central Store. Stock is added there only after it accepts.`);
        },
        error: (error: unknown) => {
          this.retPin.set('');
          this.setRet({ saving: false, formError: saveErrorMessage(error) });
          if (problemSlug(error) === 'insufficient-stock') {
            this.retKey = newClientKey();
          }
        },
      });
  }

  protected submitDamage(): void {
    const s = this.dmg();
    const row = s.row;
    if (s.saving || !row) {
      return;
    }
    const qtyError = this.qtyProblem(s.qty, row.qty);
    const reason = this.damageReasons().find((r) => r.reasonKey === s.reasonKey);
    const reasonError = reason ? null : 'Choose a reason';
    const x = row.product.schedule === 'X';
    const pinError = x ? pinProblem(this.dmgPin()) : null;
    const signerError = x && !this.dmgSigner() ? 'Choose the second signer' : null;
    const noteError = reason?.needsNote && s.note.trim().length < 4 ? 'Write a note of at least 4 characters' : null;
    this.setDmg({ qtyError, reasonError: reasonError ?? noteError, pinError, signerError, formError: null });
    if (qtyError || reasonError || noteError || pinError || signerError) {
      return;
    }
    this.setDmg({ saving: true });
    this.api
      .createDamageReport(this.storeId(), {
        clientKey: this.dmgKey,
        batchId: row.batch.batchId,
        fromState: row.state === 'QUARANTINE' ? 'QUARANTINE' : 'AVAILABLE',
        quantity: Number(s.qty.trim()),
        reasonKey: s.reasonKey ?? '',
        note: s.note.trim() === '' ? null : s.note.trim(),
        afterApproval: this.isCentral() ? 'WRITE_OFF' : this.after(),
        secondSigner: x ? { membershipId: this.dmgSigner(), pin: this.dmgPin() } : null,
      })
      .subscribe({
        next: (done) => {
          this.dmgPin.set('');
          this.dmgKey = newClientKey();
          this.dmg.set({ ...EMPTY });
          this.dmgPicker()?.reset();
          this.notice.set(
            `${done.number} reported. ${DAMAGE_STATUS_LABELS[done.status]}. The units are in quarantine and cannot be used.`,
          );
        },
        error: (error: unknown) => {
          this.dmgPin.set('');
          this.setDmg({ saving: false, formError: saveErrorMessage(error) });
          if (problemSlug(error) === 'insufficient-stock') {
            this.dmgKey = newClientKey();
          }
        },
      });
  }
}
