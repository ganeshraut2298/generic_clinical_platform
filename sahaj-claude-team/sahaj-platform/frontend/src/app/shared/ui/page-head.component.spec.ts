import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { EmptyStateComponent } from './empty-state.component';
import { PageHeadComponent } from './page-head.component';

@Component({
  imports: [PageHeadComponent, EmptyStateComponent],
  template: `
    <app-page-head title="Patients" [lead]="lead">
      <button type="button" id="act">New patient</button>
    </app-page-head>
    <app-empty>Nothing here.</app-empty>
  `,
})
class HostComponent {
  lead = 'Every patient registered at the clinic.';
}

describe('PageHeadComponent and EmptyStateComponent', () => {
  function render() {
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    return fixture;
  }

  it('shows the title, the lead and the projected actions', () => {
    const el: HTMLElement = render().nativeElement;
    expect(el.querySelector('.pagehead h1')?.textContent).toBe('Patients');
    expect(el.querySelector('.pagehead .lead')?.textContent).toContain('Every patient');
    expect(el.querySelector('.pagehead .actions #act')?.textContent).toContain('New patient');
  });

  it('leaves the lead out when there is none', () => {
    const fixture = TestBed.createComponent(HostComponent);
    fixture.componentInstance.lead = '';
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('.lead')).toBeNull();
  });

  it('renders the empty state text inside the dashed box', () => {
    const el: HTMLElement = render().nativeElement;
    expect(el.querySelector('.empty')?.textContent).toContain('Nothing here.');
  });
});
