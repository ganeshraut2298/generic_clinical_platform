import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { InViewDirective } from '../../../core/motion/in-view.directive';
import { CharacterKey, CharacterPose } from '../../../shared/character/characters';
import { CharacterComponent } from '../../../shared/character/character.component';
import { IconComponent } from '../../../shared/icon/icon.component';
import { EnquirySheet } from '../enquiry/enquiry-sheet.service';

/** Section 11: final call to action, with the whole cast along a patient's path. */
@Component({
  selector: 'app-final-cta',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, RouterLink, InViewDirective, CharacterComponent, IconComponent],
  styleUrl: './final-cta.component.scss',
  template: `
    <section
      class="final surf"
      aria-labelledby="h-final"
      appInView
      (inViewChange)="inView.set($event)"
    >
      <h2 id="h-final">Spend less time managing your clinic. More time caring for patients.</h2>
      <p class="muted">
        Set up your clinic workflow and bring your OPD operations into one connected workspace.
      </p>
      <div class="actions">
        <a mat-flat-button class="btn-lg" routerLink="/login" [queryParams]="{ mode: 'signup' }">
          Start Free <app-icon name="arrow" [size]="17" />
        </a>
        <button mat-stroked-button class="btn-lg btn-sec" type="button" (click)="bookDemo()">
          Book a Demo
        </button>
      </div>
      <div class="team-row" aria-hidden="true">
        <div class="flowline">
          @for (label of path; track label; let i = $index) {
            <span class="fl-n" [style.left.%]="8 + i * 21">{{ label }}</span>
          }
          <span class="lane"><i></i></span>
        </div>
        @for (member of cast; track member.person; let i = $index) {
          <app-character [person]="member.person" [pose]="member.pose" [delay]="i * 0.5" />
        }
      </div>
    </section>
  `,
})
export class FinalCtaComponent {
  private readonly enquiry = inject(EnquirySheet);

  protected readonly inView = signal(false);
  protected readonly path = ['Token', 'Vitals', 'Consult', 'Bill', 'Follow-up'];
  protected readonly cast: { person: CharacterKey; pose: CharacterPose }[] = [
    { person: 'pooja', pose: 'idle' },
    { person: 'anjali', pose: 'clip' },
    { person: 'meera', pose: 'tablet' },
    { person: 'kiran', pose: 'idle' },
    { person: 'sunita', pose: 'file' },
  ];

  protected bookDemo(): void {
    this.enquiry.open('BOOK_DEMO');
  }
}
