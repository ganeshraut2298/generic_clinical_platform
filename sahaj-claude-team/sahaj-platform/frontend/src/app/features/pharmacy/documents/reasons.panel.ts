import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { IconComponent } from '../../../shared/icon/icon.component';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { loadErrorMessage, problemSlug, saveErrorMessage } from '../pharmacy-display.util';
import { PharmacyApi } from '../pharmacy.api';
import { ListKey, ReasonEntry, ReasonList, ReasonLists } from '../pharmacy.model';

/**
 * A reason the server fixes can never be switched off. The server says so with the `fixed` flag (contract 0.2.1,
 * DEC-078 item 6); the screen never recognises a reason by its key.
 */
export function isFixedReason(reason: ReasonEntry): boolean {
  return reason.fixed === true;
}

/** Chip text of a reason; a switched-off reason says so in words, never by colour alone. */
export function reasonChipText(reason: ReasonEntry): string {
  return reason.active ? reason.label : `${reason.label} (off)`;
}

/**
 * "Reasons" panel (story 78-10): every list the server returns, as chips (11 lists after SAH-79; never a fixed
 * four). The owner adds a reason to a list (1 to 40 characters, not already there), switches a reason off or
 * on again, and cannot rename or delete one. A switched-off reason is not offered for new work and stays on old
 * records. A reader sees the chips and no button.
 */
@Component({
  selector: 'app-reasons',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, IconComponent],
  styles: `
    :host {
      display: block;
    }
    .list {
      padding: 10px 0;
      border-top: 1px solid var(--line);
    }
    h3 {
      margin: 0 0 8px;
      font-size: 0.95rem;
    }
    .chips {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      align-items: center;
    }
    .chip {
      display: inline-flex;
      align-items: center;
      gap: 6px;
    }
    .chip.off {
      opacity: 0.75;
      text-decoration: line-through;
    }
    .add {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      align-items: flex-start;
      margin-top: 10px;
    }
    .add mat-form-field {
      flex: 1 1 240px;
      max-width: 360px;
    }
  `,
  template: `
    <section class="panel surf" aria-labelledby="rs-h" data-testid="reasons">
      <div class="panel-h">
        <h2 id="rs-h">Reasons</h2>
      </div>
      @if (loading()) {
        <p class="loading" role="status" data-testid="reasons-loading">Loading reasons</p>
      } @else if (loadError(); as text) {
        <div class="notice err" role="alert" data-testid="reasons-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
        </div>
        <div class="actions">
          <button mat-stroked-button type="button" (click)="load()">Try again</button>
        </div>
      } @else {
        @if (actionError(); as text) {
          <div class="notice err" role="alert" data-testid="reasons-action-error">
            <app-icon name="alert" [size]="16" />
            <span>{{ text }}</span>
          </div>
        }
        @for (list of lists(); track list.listKey) {
          <div class="list" [attr.data-testid]="'reason-list-' + list.listKey">
            <h3 [id]="'rl-' + list.listKey">{{ list.label }}</h3>
            <div class="chips" role="list" [attr.aria-labelledby]="'rl-' + list.listKey">
              @for (reason of list.reasons; track reason.reasonKey) {
                <span
                  role="listitem"
                  class="pill chip"
                  [class.off]="!reason.active"
                  [class.p-teal]="reason.active"
                  [class.p-wait]="!reason.active"
                  [attr.data-testid]="'reason-' + list.listKey + '-' + reason.reasonKey"
                >
                  {{ chipText(reason) }}
                  @if (canEdit() && !fixed(reason)) {
                    <button
                      mat-button
                      type="button"
                      class="btn-sm"
                      [attr.data-testid]="'reason-toggle-' + list.listKey + '-' + reason.reasonKey"
                      [attr.aria-label]="(reason.active ? 'Switch off ' : 'Switch on ') + reason.label"
                      [disabled]="busy()"
                      (click)="toggle(list, reason)"
                    >
                      {{ reason.active ? 'Switch off' : 'Switch on' }}
                    </button>
                  }
                </span>
              }
            </div>
            @if (canEdit()) {
              <div class="add">
                <mat-form-field appearance="outline" subscriptSizing="dynamic">
                  <mat-label>New reason</mat-label>
                  <input
                    matInput
                    [formControl]="control(list.listKey)"
                    maxlength="40"
                    autocomplete="off"
                    [attr.data-testid]="'reason-input-' + list.listKey"
                  />
                </mat-form-field>
                <button
                  mat-stroked-button
                  type="button"
                  [disabled]="busy()"
                  [attr.data-testid]="'reason-add-' + list.listKey"
                  [attr.aria-label]="'Add a reason to ' + list.label"
                  (click)="add(list)"
                >
                  Add reason
                </button>
              </div>
              @if (errorOf(list.listKey); as text) {
                <span class="err-text" role="alert" [attr.data-testid]="'reason-error-' + list.listKey">{{ text }}</span>
              }
            }
          </div>
        }
        <p class="note">
          A switched-off reason is not offered for new work and stays on old records. Reasons are never deleted
          and the ones Sahaj supplies are never renamed.
        </p>
      }
    </section>
  `,
})
export class ReasonsPanel implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly context = inject(PharmacyContextStore);

  protected readonly canEdit = this.context.canEditSetup;
  protected readonly chipText = reasonChipText;
  protected readonly fixed = isFixedReason;
  protected readonly lists = signal<ReasonList[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);
  protected readonly busy = signal(false);

  private readonly controls = new Map<ListKey, FormControl<string>>();
  private readonly errors = signal<Partial<Record<ListKey, string>>>({});

  ngOnInit(): void {
    this.load();
  }

  protected control(key: ListKey): FormControl<string> {
    let control = this.controls.get(key);
    if (!control) {
      control = new FormControl('', { nonNullable: true });
      this.controls.set(key, control);
    }
    return control;
  }

  protected errorOf(key: ListKey): string | null {
    return this.errors()[key] ?? null;
  }

  protected load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.api.reasonLists().subscribe({
      next: (answer) => {
        this.lists.set(answer.lists);
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.loadError.set(loadErrorMessage(error));
      },
    });
  }

  private setError(key: ListKey, text: string | null): void {
    this.errors.update((current) => {
      const next = { ...current };
      if (text === null) {
        delete next[key];
      } else {
        next[key] = text;
      }
      return next;
    });
  }

  protected add(list: ReasonList): void {
    if (this.busy()) {
      return;
    }
    const control = this.control(list.listKey);
    const label = control.value.trim();
    this.actionError.set(null);
    if (label === '' || label.length > 40) {
      this.setError(list.listKey, 'Enter the reason (up to 40 characters)');
      return;
    }
    this.setError(list.listKey, null);
    this.busy.set(true);
    this.api.addReason(list.listKey, { label }).subscribe({
      next: (answer: ReasonLists) => {
        this.busy.set(false);
        this.lists.set(answer.lists);
        control.reset('');
      },
      error: (error: unknown) => {
        this.busy.set(false);
        if (problemSlug(error) === 'reason-exists') {
          this.setError(list.listKey, 'That reason is already in the list');
          return;
        }
        this.setError(list.listKey, saveErrorMessage(error));
      },
    });
  }

  protected toggle(list: ReasonList, reason: ReasonEntry): void {
    if (this.busy()) {
      return;
    }
    this.actionError.set(null);
    this.busy.set(true);
    const call = reason.active
      ? this.api.switchOffReason(list.listKey, reason.reasonKey)
      : this.api.switchOnReason(list.listKey, reason.reasonKey);
    call.subscribe({
      next: (answer) => {
        this.busy.set(false);
        this.lists.set(answer.lists);
      },
      error: (error: unknown) => {
        this.busy.set(false);
        // "Keep at least one damage reason." and "This reason is fixed." are the server's sentences.
        this.actionError.set(saveErrorMessage(error));
      },
    });
  }
}
