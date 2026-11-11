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

  it('marks the lead as reserved only when asked (SAH-37 D-11), keeping its text in the page', () => {
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    const lead = (fixture.nativeElement as HTMLElement).querySelector('.lead')!;
    expect(lead.classList.contains('reserve')).toBe(false);
    fixture.destroy();

    const head = TestBed.createComponent(PageHeadComponent);
    head.componentRef.setInput('title', 'IPD');
    head.componentRef.setInput('lead', 'Beds and admissions');
    head.componentRef.setInput('reserveLead', true);
    head.detectChanges();
    const reserved = (head.nativeElement as HTMLElement).querySelector('.lead')!;
    expect(reserved.classList.contains('reserve')).toBe(true);
    expect(reserved.textContent).toBe('Beds and admissions');
    expect(reserved.closest('[aria-hidden=true]')).toBeNull();
  });

  it('sizes a reserved lead with a hidden copy of the expected words that is not in the page text', () => {
    const head = TestBed.createComponent(PageHeadComponent);
    head.componentRef.setInput('title', 'IPD');
    head.componentRef.setInput('lead', 'Beds and admissions');
    head.componentRef.setInput('reserveLead', true);
    head.componentRef.setInput('leadLike', 'Beds and admissions · Bed and nursing charges post at midnight');
    head.detectChanges();
    const el = head.nativeElement as HTMLElement;
    const sizer = el.querySelector('.lead-hold > .sizer')!;
    expect(sizer.getAttribute('data-like')).toBe('Beds and admissions · Bed and nursing charges post at midnight');
    expect(sizer.textContent).toBe('');
    expect(el.querySelector('.lead-hold > p.lead')!.textContent).toBe('Beds and admissions');
    expect(el.querySelectorAll('.lead').length).toBe(2);
  });

  it('copies the lead itself when no expected words are given, and draws a plain lead when not reserved', () => {
    const head = TestBed.createComponent(PageHeadComponent);
    head.componentRef.setInput('title', 'IPD');
    head.componentRef.setInput('lead', 'Beds and admissions');
    head.componentRef.setInput('reserveLead', true);
    head.detectChanges();
    const el = head.nativeElement as HTMLElement;
    expect(el.querySelector('.sizer')!.getAttribute('data-like')).toBe('Beds and admissions');

    head.componentRef.setInput('reserveLead', false);
    head.detectChanges();
    expect(el.querySelector('.lead-hold')).toBeNull();
    expect(el.querySelector('.sizer')).toBeNull();
    expect(el.querySelectorAll('.lead').length).toBe(1);
    expect(el.querySelector('.pagehead > div > p.lead')!.textContent).toBe('Beds and admissions');
  });

  it('renders the empty state text inside the dashed box', () => {
    const el: HTMLElement = render().nativeElement;
    expect(el.querySelector('.empty')?.textContent).toContain('Nothing here.');
  });
});
