import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatSnackBar } from '@angular/material/snack-bar';
import { switchMap, tap, throwError } from 'rxjs';
import { ApiError } from '../../../core/http/problem-detail';
import { ShellStore } from '../../../core/shell/shell.store';
import { IconComponent } from '../../../shared/icon/icon.component';
import { problemMessage } from '../config-format';
import { RulesApi } from './rules.api';
import { AlertRule, AlertRules, AlertRuleSuggestion } from './rules.model';

export const RULE_EXISTS = 'That rule already exists.';

/**
 * Alerts and escalation (SAH-18), mockup `cfgRules`. Stores and manages rules only: the engine
 * that checks them is a later release, so the mockup's "Ran N times" line is not shown.
 */
@Component({
  selector: 'app-rules-config',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, MatButtonModule, IconComponent],
  styleUrl: './rules.page.scss',
  templateUrl: './rules.page.html',
})
export class RulesPage implements OnInit {
  private readonly api = inject(RulesApi);
  private readonly store = inject(ShellStore);
  private readonly snackBar = inject(MatSnackBar);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly data = signal<AlertRules | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly confirmIndex = signal<number | null>(null);

  protected readonly isOwner = computed(() => this.store.me()?.role === 'OWNER');
  protected readonly onCount = computed(
    () => (this.data()?.rules ?? []).filter((r) => r.enabled).length,
  );

  protected readonly builder = new FormGroup({
    when: new FormControl('', { nonNullable: true }),
    cond: new FormControl('', { nonNullable: true }),
    then: new FormControl('', { nonNullable: true }),
  });

  ngOnInit(): void {
    this.api
      .get()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (data) => {
          this.apply(data);
          this.loading.set(false);
        },
        error: (error: unknown) => {
          this.loading.set(false);
          this.loadError.set(problemMessage(error, 'We could not load the rules. Try again.'));
        },
      });
  }

  protected add(): void {
    if (!this.isOwner() || this.busy()) {
      return;
    }
    const { when, cond, then } = this.builder.getRawValue();
    if (!when || !cond || !then) {
      return;
    }
    this.run(this.api.add({ when, cond, then }), true, 'We could not add the rule. Try again.');
  }

  protected addSuggestion(suggestion: AlertRuleSuggestion): void {
    if (!this.isOwner() || this.busy()) {
      return;
    }
    this.run(
      this.api.addSuggestion(suggestion.index),
      true,
      'We could not add the rule. Try again.',
    );
  }

  protected toggle(rule: AlertRule): void {
    if (!this.isOwner() || this.busy()) {
      return;
    }
    this.withId(
      rule,
      (id) => this.api.setEnabled(id, !rule.enabled),
      'We could not change the rule. Try again.',
    );
  }

  protected askRemove(index: number): void {
    this.confirmIndex.set(index);
  }

  protected cancelRemove(): void {
    this.confirmIndex.set(null);
  }

  protected remove(rule: AlertRule): void {
    this.confirmIndex.set(null);
    if (!this.isOwner() || this.busy()) {
      return;
    }
    this.withId(rule, (id) => this.api.remove(id), 'We could not delete the rule. Try again.');
  }

  protected sentence(item: { when: string; cond: string; then: string }): string {
    const lower = (text: string) => text.charAt(0).toLowerCase() + text.slice(1);
    return `If ${item.when.toLowerCase()} ${item.cond} → ${lower(item.then)}`;
  }

  /** Starter rules have no id until saved: save them once, then act on the matching saved rule. */
  private withId(
    rule: AlertRule,
    call: (id: string) => ReturnType<RulesApi['get']>,
    fallback: string,
  ): void {
    if (rule.id) {
      this.run(call(rule.id), false, fallback);
      return;
    }
    const starter = this.api.saveStarter().pipe(
      tap((saved) => this.apply(saved)),
      switchMap((saved) => {
        const match = saved.rules.find(
          (r) => r.when === rule.when && r.cond === rule.cond && r.then === rule.then,
        );
        return match?.id ? call(match.id) : throwError(() => new Error('Rule not found'));
      }),
    );
    this.run(starter, false, fallback);
  }

  private run(call: ReturnType<RulesApi['get']>, added: boolean, fallback: string): void {
    this.busy.set(true);
    call.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (data) => {
        this.busy.set(false);
        this.apply(data);
        if (added) {
          this.snackBar.open('Rule added and switched on', 'Close', { duration: 4000 });
        }
      },
      error: (error: unknown) => {
        this.busy.set(false);
        const conflict = error instanceof ApiError && error.problem.status === 409;
        const message = conflict ? RULE_EXISTS : problemMessage(error, fallback);
        this.snackBar.open(message, 'Close', { duration: 5000 });
      },
    });
  }

  private apply(data: AlertRules): void {
    this.data.set(data);
    const current = this.builder.getRawValue();
    const pick = (value: string, options: string[]) =>
      options.includes(value) ? value : (options[0] ?? '');
    this.builder.setValue({
      when: pick(current.when, data.options.when),
      cond: pick(current.cond, data.options.cond),
      then: pick(current.then, data.options.then),
    });
    if (!this.isOwner()) {
      this.builder.disable({ emitEvent: false });
    }
  }
}
