import { TestBed } from '@angular/core/testing';
import { MatDialogRef } from '@angular/material/dialog';
import { ActivatedRouteSnapshot, RouterStateSnapshot } from '@angular/router';
import { Observable, of } from 'rxjs';
import { LeaveAware, ipCardLeaveGuard } from './ipcard-leave.guard';
import { CardLeaveDialogComponent, LEAVE_TEXT, LEAVE_TITLE } from './leave-dialog.component';

describe('ipCardLeaveGuard', () => {
  const run = (page: LeaveAware) =>
    TestBed.runInInjectionContext(() =>
      ipCardLeaveGuard(
        page,
        {} as ActivatedRouteSnapshot,
        {} as RouterStateSnapshot,
        {} as RouterStateSnapshot,
      ),
    );

  it('lets the router leave when the page has nothing unsaved', () => {
    expect(run({ confirmLeave: () => true })).toBe(true);
  });

  it('waits for the answer of the warning when the page has unsaved edits', () => {
    const answers: boolean[] = [];
    (run({ confirmLeave: () => of(false) }) as Observable<boolean>).subscribe((v) =>
      answers.push(v),
    );
    (run({ confirmLeave: () => of(true) }) as Observable<boolean>).subscribe((v) =>
      answers.push(v),
    );
    expect(answers).toEqual([false, true]);
  });
});

describe('CardLeaveDialogComponent', () => {
  it('warns that unsaved changes are lost, keeps a saved draft, and closes with the choice', () => {
    const close = vi.fn();
    TestBed.configureTestingModule({ providers: [{ provide: MatDialogRef, useValue: { close } }] });
    const fixture = TestBed.createComponent(CardLeaveDialogComponent);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain(LEAVE_TITLE);
    expect(el.textContent).toContain(
      'Your unsaved changes will be lost. A draft you already saved is kept.',
    );
    expect(LEAVE_TEXT).toContain('unsaved');
    el.querySelector<HTMLButtonElement>('[data-act=leave-stay]')!.click();
    expect(close).toHaveBeenLastCalledWith(false);
    el.querySelector<HTMLButtonElement>('[data-act=leave-go]')!.click();
    expect(close).toHaveBeenLastCalledWith(true);
  });
});
