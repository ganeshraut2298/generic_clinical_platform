import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { IconComponent } from '../../../shared/icon/icon.component';
import { TranslatePipe } from '../../../core/i18n/translate.pipe';
import { NavItem } from '../../../core/shell/nav.config';

export interface GoToOption extends NavItem {
  label: string;
}

/**
 * The "Go to…" palette (Ctrl K in the mockup's `openCmd`/`cmdList`): only pages the signed-in
 * role and plan allow, searchable, arrow keys plus Enter to open, Esc to close (SAH-5 criterion 9).
 * Patient search is out of scope here; it waits for the patient story.
 */
@Component({
  selector: 'app-go-to-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, MatDialogModule, IconComponent, TranslatePipe],
  styleUrl: './go-to-dialog.component.scss',
  template: `
    <div class="cmdsheet" role="dialog" aria-modal="true" aria-label="Go to">
      <label class="cmdin">
        <app-icon name="search" [size]="18" />
        <input
          #searchInput
          type="search"
          autocomplete="off"
          [attr.placeholder]="'shell.goToPlaceholder' | t"
          [attr.aria-label]="'shell.goToPlaceholder' | t"
          [(ngModel)]="query"
          (ngModelChange)="onQueryChange()"
          (keydown)="onKeydown($event)"
        />
      </label>
      <div class="cmdres" role="listbox" aria-label="Results">
        @for (item of filtered(); track item.key; let i = $index) {
          <button
            type="button"
            class="cmdi"
            [class.on]="i === activeIndex()"
            role="option"
            [attr.aria-selected]="i === activeIndex()"
            (click)="choose(item)"
          >
            <app-icon [name]="item.icon" [size]="16" />
            <span>{{ item.label }}</span>
          </button>
        } @empty {
          <div class="empty">{{ 'shell.noMatches' | t }}</div>
        }
      </div>
      <div class="cmdfoot">
        <span><kbd>&uarr;</kbd><kbd>&darr;</kbd> move</span>
        <span><kbd>Enter</kbd> open</span>
        <span><kbd>Esc</kbd> close</span>
      </div>
    </div>
  `,
})
export class GoToDialogComponent {
  private readonly dialogRef = inject(MatDialogRef<GoToDialogComponent, string | undefined>);
  protected readonly options = inject<GoToOption[]>(MAT_DIALOG_DATA);

  protected query = '';
  private readonly querySig = signal('');
  protected readonly activeIndex = signal(0);

  protected readonly filtered = computed(() => {
    const q = this.querySig().trim().toLowerCase();
    if (!q) {
      return this.options;
    }
    return this.options.filter((item) => item.label.toLowerCase().includes(q));
  });

  protected onQueryChange(): void {
    this.querySig.set(this.query);
    this.activeIndex.set(0);
  }

  protected onKeydown(event: KeyboardEvent): void {
    const items = this.filtered();
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      this.activeIndex.set(Math.min(items.length - 1, this.activeIndex() + 1));
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      this.activeIndex.set(Math.max(0, this.activeIndex() - 1));
    } else if (event.key === 'Enter') {
      event.preventDefault();
      const item = items[this.activeIndex()];
      if (item) {
        this.choose(item);
      }
    } else if (event.key === 'Escape') {
      this.dialogRef.close(undefined);
    }
  }

  protected choose(item: GoToOption): void {
    this.dialogRef.close(item.key);
  }
}
