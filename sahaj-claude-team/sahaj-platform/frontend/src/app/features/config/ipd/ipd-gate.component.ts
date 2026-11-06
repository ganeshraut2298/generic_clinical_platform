import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import { IconComponent } from '../../../shared/icon/icon.component';
import { IpdGate } from './ipd-setup.store';

/** What the two IPD setup pages show instead of their content while IPD is off or unknown. */
@Component({
  selector: 'app-ipd-gate',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, IconComponent],
  styleUrls: ['../config-common.scss'],
  styles: `
    .linkbtn {
      border: 0;
      background: transparent;
      color: var(--pri-text);
      font-weight: 600;
      text-decoration: underline;
      min-height: 44px;
      padding: 0 8px;
    }
  `,
  template: `
    <section class="panel surf" [attr.aria-busy]="gate() === 'checking'">
      @switch (gate()) {
        @case ('off') {
          <div class="notice warn" role="status">
            <app-icon name="lock" [size]="16" />
            <span>
              In-patient care (IPD) is not on for your clinic.
              <a routerLink="../modules">Open Plan and modules</a> to ask for it.
            </span>
          </div>
        }
        @case ('error') {
          <div class="notice err" role="alert">
            <app-icon name="alert" [size]="16" />
            <span>We could not check your plan. </span>
            <button type="button" class="linkbtn" (click)="retry.emit()">Try again</button>
          </div>
        }
        @default {
          <p class="muted">Checking your plan</p>
        }
      }
    </section>
  `,
})
export class IpdGateComponent {
  readonly gate = input.required<IpdGate>();
  readonly retry = output<void>();
}
