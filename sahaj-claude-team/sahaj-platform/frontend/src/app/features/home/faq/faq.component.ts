import { ChangeDetectionStrategy, Component } from '@angular/core';
import { SectionHeadComponent } from '../shared/section-head.component';

export const FAQ: readonly { question: string; answer: string }[] = [
  {
    question: 'What happens after the free year?',
    answer:
      'You choose Professional at ₹1,999 a year or ₹299 a month. We remind you 60, 30 and 7 days before. If you stop, your data stays readable for 90 days and you can export all of it.',
  },
  {
    question: 'Is WhatsApp really included?',
    answer:
      "Yes. Each clinic gets 1,000 WhatsApp messages a month for tokens, receipts, prescriptions and reminders, sent in your clinic's name. Top-up packs are available.",
  },
  {
    question: 'Where is patient data stored?',
    answer:
      'On servers in India, encrypted, with daily backups kept for 30 days. Each staff member sees only what their role allows, and every change is logged.',
  },
  {
    question: 'Do I need a computer?',
    answer:
      'No. The front desk works on a phone, tablet or computer. Doctors use the Doctor App on an Android tablet or phone.',
  },
  {
    question: 'What if the internet goes down?',
    answer:
      'The current visit is not lost. Tokens and bills keep working and sync when the connection returns.',
  },
  {
    question: 'Can you move my old patient data?',
    answer:
      'Yes. Assisted import from Excel or your current software is available, and old paper files can be photographed into the history.',
  },
];

/** Section 10: Questions. The first answer is open, as in the mockup. */
@Component({
  selector: 'app-faq-section',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SectionHeadComponent],
  styleUrl: './faq.component.scss',
  template: `
    <section class="lp-sect" id="faq" tabindex="-1" aria-labelledby="h-faq">
      <app-section-head headingId="h-faq" eyebrow="Questions" heading="What doctors ask us" />
      <div class="faq">
        @for (item of items; track item.question; let i = $index) {
          <details class="surf" [open]="i === 0">
            <summary>{{ item.question }}</summary>
            <p>{{ item.answer }}</p>
          </details>
        }
      </div>
    </section>
  `,
})
export class FaqSectionComponent {
  protected readonly items = FAQ;
}
