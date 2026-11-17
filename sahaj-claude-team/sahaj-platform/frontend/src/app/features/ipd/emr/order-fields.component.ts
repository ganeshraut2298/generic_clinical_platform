import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { map, startWith, switchMap } from 'rxjs';
import { ReactiveFormsModule } from '@angular/forms';
import { KeyLabel, OrderErrors, OrderField } from './emr.model';
import { OrderForm, isPrn, isStat, statNote } from './order-form.logic';

/**
 * The order fields after "Medicine" (SAH-76-1; field order NEW, OQ-76-28): How often, Dose with its unit,
 * Route, Start, Duration, Indication, Priority, then the PRN fields. STAT hides How often and Duration and
 * says "A STAT order is one dose, due now." The Change form uses the same fields without Start.
 * Presentational: the parent owns the form, the errors and the request.
 */
@Component({
  selector: 'app-order-fields',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule],
  styleUrl: './order-fields.component.scss',
  template: `
    <div class="grid-f" [formGroup]="form()" data-testid="order-fields">
      @if (!stat()) {
        <div class="field">
          <label [for]="id('freq')">How often</label>
          <select
            [id]="id('freq')"
            class="input"
            formControlName="freq"
            (change)="selection.emit()"
            [attr.aria-invalid]="errors().frequencyKey ? 'true' : null"
            [attr.aria-describedby]="errors().frequencyKey ? id('freq') + '-err' : null"
          >
            @for (f of frequencies(); track f.key) {
              <option [value]="f.key">{{ f.label }}</option>
            }
          </select>
          @if (errors().frequencyKey; as message) {
            <span class="err-text" role="alert" [id]="id('freq') + '-err'">{{ message }}</span>
          }
        </div>
      }

      <div class="field">
        <label [for]="id('dose')">Dose</label>
        <div class="pair">
          <input
            [id]="id('dose')"
            class="input num"
            formControlName="dose"
            inputmode="decimal"
            autocomplete="off"
            (input)="edited('doseAmount')"
            [attr.aria-invalid]="errors().doseAmount ? 'true' : null"
            [attr.aria-describedby]="errors().doseAmount ? id('dose') + '-err' : null"
          />
          <select
            [id]="id('unit')"
            class="input"
            formControlName="unit"
            aria-label="Dose unit"
            (change)="edited('doseUnit')"
            [attr.aria-invalid]="errors().doseUnit ? 'true' : null"
          >
            @for (u of units(); track u.key) {
              <option [value]="u.key">{{ u.label }}</option>
            }
          </select>
        </div>
        @if (errors().doseAmount ?? errors().doseUnit; as message) {
          <span class="err-text" role="alert" [id]="id('dose') + '-err'">{{ message }}</span>
        }
      </div>

      <div class="field">
        <label [for]="id('route')">Route</label>
        <select
          [id]="id('route')"
          class="input"
          formControlName="route"
          (change)="edited('routeKey')"
          [attr.aria-invalid]="errors().routeKey ? 'true' : null"
          [attr.aria-describedby]="errors().routeKey ? id('route') + '-err' : null"
        >
          @for (r of routes(); track r.key) {
            <option [value]="r.key">{{ r.label }}</option>
          }
        </select>
        @if (errors().routeKey; as message) {
          <span class="err-text" role="alert" [id]="id('route') + '-err'">{{ message }}</span>
        }
      </div>

      @if (showStart() && !stat()) {
        <div class="field">
          <label [for]="id('start')">Start</label>
          <input
            [id]="id('start')"
            class="input"
            type="datetime-local"
            formControlName="start"
            (input)="edited('startAt')"
            [attr.aria-invalid]="errors().startAt ? 'true' : null"
            [attr.aria-describedby]="id('start') + '-hint'"
          />
          <small class="faint" [id]="id('start') + '-hint'">Leave empty to start now.</small>
          @if (errors().startAt; as message) {
            <span class="err-text" role="alert">{{ message }}</span>
          }
        </div>
      }

      @if (!stat()) {
        <div class="field">
          <label [for]="id('duration')">Duration (days)</label>
          <input
            [id]="id('duration')"
            class="input num"
            formControlName="duration"
            inputmode="numeric"
            autocomplete="off"
            placeholder="Until stopped"
            (input)="edited('durationDays')"
            [attr.aria-invalid]="errors().durationDays ? 'true' : null"
            [attr.aria-describedby]="errors().durationDays ? id('duration') + '-err' : null"
          />
          @if (errors().durationDays; as message) {
            <span class="err-text" role="alert" [id]="id('duration') + '-err'">{{ message }}</span>
          }
        </div>
      }

      <div class="field">
        <label [for]="id('indication')">{{ prn() ? 'Indication' : 'Indication (optional)' }}</label>
        <input
          [id]="id('indication')"
          class="input"
          formControlName="indication"
          autocomplete="off"
          (input)="edited('indication')"
          [attr.aria-invalid]="errors().indication ? 'true' : null"
          [attr.aria-describedby]="errors().indication ? id('indication') + '-err' : null"
        />
        @if (errors().indication; as message) {
          <span class="err-text" role="alert" [id]="id('indication') + '-err'">{{ message }}</span>
        }
      </div>

      <div class="field">
        <label [for]="id('priority')">Priority</label>
        <select [id]="id('priority')" class="input" formControlName="priority" (change)="selection.emit()">
          @for (p of priorities(); track p.key) {
            <option [value]="p.key">{{ p.label }}</option>
          }
        </select>
      </div>

      @if (prn()) {
        <div class="field">
          <label [for]="id('interval')">Minimum interval</label>
          <div class="pair">
            <input
              [id]="id('interval')"
              class="input num"
              formControlName="prnInterval"
              inputmode="numeric"
              autocomplete="off"
              (input)="edited('prnMinIntervalMinutes')"
              [attr.aria-invalid]="errors().prnMinIntervalMinutes ? 'true' : null"
              [attr.aria-describedby]="errors().prnMinIntervalMinutes ? id('interval') + '-err' : null"
            />
            <select class="input" formControlName="prnUnit" aria-label="Interval unit" (change)="edited('prnMinIntervalMinutes')">
              <option value="min">minutes</option>
              <option value="h">hours</option>
            </select>
          </div>
          @if (errors().prnMinIntervalMinutes; as message) {
            <span class="err-text" role="alert" [id]="id('interval') + '-err'">{{ message }}</span>
          }
        </div>
        <div class="field">
          <label [for]="id('max')">Most doses in 24 hours</label>
          <input
            [id]="id('max')"
            class="input num"
            formControlName="prnMax"
            inputmode="numeric"
            autocomplete="off"
            (input)="edited('prnMaxPer24h')"
            [attr.aria-invalid]="errors().prnMaxPer24h ? 'true' : null"
            [attr.aria-describedby]="errors().prnMaxPer24h ? id('max') + '-err' : null"
          />
          @if (errors().prnMaxPer24h; as message) {
            <span class="err-text" role="alert" [id]="id('max') + '-err'">{{ message }}</span>
          }
        </div>
      }
    </div>
    @if (stat()) {
      <p class="faint stat-note" data-testid="stat-note">{{ statText() }}</p>
    }
  `,
})
export class OrderFieldsComponent {
  readonly form = input.required<OrderForm>();
  readonly errors = input<OrderErrors>({});
  readonly frequencies = input.required<KeyLabel[]>();
  readonly units = input.required<KeyLabel[]>();
  readonly routes = input.required<KeyLabel[]>();
  readonly priorities = input.required<KeyLabel[]>();
  readonly statWindowMinutes = input(15);
  readonly showStart = input(true);
  /** Makes the control ids unique when two forms can be on screen. */
  readonly prefix = input('ord');

  /** How often or Priority changed: the check on screen is about something else now. */
  readonly selection = output<void>();
  /** A field was typed in: its error goes. */
  readonly edit = output<OrderField>();

  /** The two fields that change what is shown, followed through the form's own changes. */
  private readonly shape = toSignal(
    toObservable(this.form).pipe(
      switchMap((f) =>
        f.valueChanges.pipe(
          startWith(null),
          map(() => ({ priority: f.controls.priority.value, freq: f.controls.freq.value })),
        ),
      ),
    ),
    { initialValue: { priority: 'ROUTINE', freq: '' } },
  );
  protected readonly stat = computed(() => isStat(this.shape()));
  protected readonly prn = computed(() => isPrn(this.shape()));
  protected readonly statText = computed(() => statNote(this.statWindowMinutes()));

  protected id(name: string): string {
    return `${this.prefix()}-${name}`;
  }

  protected edited(field: OrderField): void {
    this.edit.emit(field);
  }
}
