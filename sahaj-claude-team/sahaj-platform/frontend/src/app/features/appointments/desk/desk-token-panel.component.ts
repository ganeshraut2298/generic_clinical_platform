import { DOCUMENT } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  ViewEncapsulation,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { ShellStore } from '../../../core/shell/shell.store';
import { formatDisplayDate, formatTimeOfInstant } from '../../../shared/format/ist-date';
import { WHATSAPP_NOT_IN_PHASE } from '../../../shared/whatsapp';
import { DeskApi } from './desk.api';
import { DeskTokenPanel, shortDoctorName, tokenText, tokenWaitText } from './desk.model';

/**
 * Token panel (SAH-92-2): the token, the patient, the doctor and room, the wait line, "Print slip" (the
 * browser's print of a small slip: clinic, token, patient, doctor, date and time, nothing clinical) and
 * a disabled WhatsApp button. Opened after check-in with the answer's numbers, or from a card, where it reads
 * the current numbers. Readable and printable by every role.
 */
@Component({
  selector: 'app-desk-token-panel',
  imports: [MatButtonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  styleUrl: './desk-token-panel.component.scss',
  templateUrl: './desk-token-panel.component.html',
})
export class DeskTokenPanelComponent {
  private readonly api = inject(DeskApi);
  private readonly store = inject(ShellStore);
  private readonly doc = inject(DOCUMENT);

  readonly visitId = input.required<string>();
  /** The numbers from the check-in answer; null when the panel is opened from a card. */
  readonly initial = input<DeskTokenPanel | null>(null);
  readonly issued = input(false);

  readonly closePanel = output<void>();

  protected readonly panel = signal<DeskTokenPanel | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly whatsappNote = WHATSAPP_NOT_IN_PHASE;
  protected readonly shortName = shortDoctorName;
  protected readonly token = tokenText;
  protected readonly wait = tokenWaitText;
  protected readonly clinicName = computed(() => this.store.me()?.clinic?.name ?? '');
  protected readonly slipDate = computed(() => {
    const p = this.panel();
    return p ? formatDisplayDate(p.tokenDate) : '';
  });
  protected readonly slipTime = computed(() => formatTimeOfInstant(this.panel()?.checkedInAt));

  private seq = 0;

  constructor() {
    effect(() => {
      const id = this.visitId();
      const initial = this.initial();
      untracked(() => {
        if (initial && initial.visitId === id) {
          this.seq++;
          this.panel.set(initial);
          this.loading.set(false);
          this.error.set(null);
        } else {
          this.panel.set(null);
          this.load(id);
        }
      });
    });
  }

  protected reload(): void {
    this.load(this.visitId());
  }

  protected print(): void {
    this.doc.defaultView?.print();
  }

  private load(id: string): void {
    const seq = ++this.seq;
    this.loading.set(true);
    this.error.set(null);
    this.api.getTokenPanel(id).subscribe({
      next: (result) => {
        if (seq !== this.seq) {
          return;
        }
        this.panel.set(result);
        this.loading.set(false);
      },
      error: () => {
        if (seq !== this.seq) {
          return;
        }
        this.loading.set(false);
        this.error.set('We could not load the token. Try again.');
      },
    });
  }
}
