import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideReducedMotion } from '../../testing/motion-testing';
import { HERO_FINAL_STEP, HeroComponent } from './hero/hero.component';
import { JourneySectionComponent } from './journey/journey.component';
import { PricingSectionComponent } from './pricing/pricing.component';
import { RolesSectionComponent } from './roles/roles.component';
import { SETUP_DONE, SetupSectionComponent } from './setup/setup.component';
import { WorkflowSectionComponent } from './workflow/workflow.component';

async function mount<T>(type: new () => T, reduced: boolean): Promise<ComponentFixture<T>> {
  await TestBed.configureTestingModule({
    imports: [type as never],
    providers: [provideRouter([]), provideReducedMotion(reduced)],
  }).compileComponents();
  const fixture = TestBed.createComponent(type);
  fixture.detectChanges();
  await fixture.whenStable();
  return fixture;
}

describe('animated scenes with reduced motion', () => {
  afterEach(() => vi.useRealTimers());

  it('hero shows its final state and never plays', async () => {
    vi.useFakeTimers();
    const fixture = await mount(HeroComponent, true);
    vi.advanceTimersByTime(20000);
    expect(fixture.componentInstance.step()).toBe(HERO_FINAL_STEP);
    expect(fixture.componentInstance.playing()).toBe(false);
    expect(fixture.nativeElement.querySelector('.hstage.playing')).toBeNull();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('setup shows the clinic ready', async () => {
    vi.useFakeTimers();
    const fixture = await mount(SetupSectionComponent, true);
    vi.advanceTimersByTime(20000);
    expect(fixture.componentInstance.current()).toBe(SETUP_DONE);
    expect(fixture.nativeElement.textContent).toContain('Your clinic workspace is ready.');
  });

  it('setup Replay goes back to step 1 without animating', async () => {
    vi.useFakeTimers();
    const fixture = await mount(SetupSectionComponent, true);
    const replay = Array.from(fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>).find(
      (b) => b.textContent!.includes('Replay'),
    )!;
    replay.click();
    expect(fixture.componentInstance.current()).toBe(0);
    vi.advanceTimersByTime(10000);
    expect(fixture.componentInstance.current()).toBe(0);
  });

  it('workflow already has the Vitals stage before Consultation', async () => {
    vi.useFakeTimers();
    const fixture = await mount(WorkflowSectionComponent, true);
    vi.advanceTimersByTime(10000);
    const list = fixture.componentInstance.workflow();
    expect(list.indexOf('vitals')).toBe(list.indexOf('consult') - 1);
  });

  it('workflow preview shows the end at once', async () => {
    vi.useFakeTimers();
    const fixture = await mount(WorkflowSectionComponent, true);
    const preview = Array.from(fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>).find(
      (b) => b.textContent!.includes('Preview with a sample patient'),
    )!;
    preview.click();
    expect(fixture.componentInstance.caption()).toBe('The sample patient passed every stage.');
    vi.advanceTimersByTime(5000);
    expect(fixture.componentInstance.caption()).toBe('The sample patient passed every stage.');
    expect(fixture.nativeElement.querySelector('.node.active')).toBeNull();
  });

  it('roles shows all steps done', async () => {
    vi.useFakeTimers();
    const fixture = await mount(RolesSectionComponent, true);
    vi.advanceTimersByTime(20000);
    expect(fixture.componentInstance.step()).toBe(6);
    expect(fixture.nativeElement.textContent).toContain('All 6 steps done. Visit closed with nothing missed.');
  });

  it('journey and pricing do not change by themselves', async () => {
    vi.useFakeTimers();
    const journey = await mount(JourneySectionComponent, true);
    vi.advanceTimersByTime(30000);
    expect(journey.componentInstance.current()).toBe(3);
    expect(vi.getTimerCount()).toBe(0);
    TestBed.resetTestingModule();
    const pricing = await mount(PricingSectionComponent, true);
    vi.advanceTimersByTime(30000);
    expect(pricing.componentInstance.growIndex()).toBe(1);
  });
});

describe('animated scenes when motion is allowed and the scene is in view', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  async function tick(fixture: ComponentFixture<unknown>, ms: number) {
    vi.advanceTimersByTime(ms);
    fixture.detectChanges();
  }

  it('hero steps through the scene and loops', async () => {
    const fixture = await mount(HeroComponent, false);
    const hero = fixture.componentInstance;
    expect(hero.playing()).toBe(true);
    expect(hero.step()).toBe(0);
    await tick(fixture, 700 * 4);
    expect(hero.step()).toBe(4);
    await tick(fixture, 700 * 6);
    expect(hero.step()).toBe(HERO_FINAL_STEP);
  });

  it('setup walks the seven steps, then the clinic is ready', async () => {
    const fixture = await mount(SetupSectionComponent, false);
    const setup = fixture.componentInstance;
    expect(setup.current()).toBe(0);
    await tick(fixture, 1300 * 3);
    expect(setup.current()).toBe(3);
    await tick(fixture, 1300 * 4);
    expect(setup.current()).toBe(SETUP_DONE);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('setup stops when the visitor picks a step', async () => {
    const fixture = await mount(SetupSectionComponent, false);
    const buttons = fixture.nativeElement.querySelectorAll('.sst') as NodeListOf<HTMLButtonElement>;
    buttons[4].click();
    expect(fixture.componentInstance.current()).toBe(4);
    await tick(fixture, 10000);
    expect(fixture.componentInstance.current()).toBe(4);
  });

  it('roles hands the visit from person to person', async () => {
    const fixture = await mount(RolesSectionComponent, false);
    expect(fixture.componentInstance.step()).toBe(0);
    await tick(fixture, 1400 * 2);
    expect(fixture.componentInstance.step()).toBe(2);
    await tick(fixture, 1400 * 4);
    expect(fixture.componentInstance.step()).toBe(6);
  });

  it('workflow adds Vitals on its own, then previews a sample patient', async () => {
    const fixture = await mount(WorkflowSectionComponent, false);
    const workflow = fixture.componentInstance;
    expect(workflow.workflow().includes('vitals')).toBe(false);
    await tick(fixture, 1400);
    expect(workflow.workflow().includes('vitals')).toBe(true);
    await tick(fixture, 1200 + 700 * 9);
    expect(workflow.caption()).toBe('The sample patient passed every stage.');
  });

  it('journey changes stage by itself until the visitor picks one', async () => {
    const fixture = await mount(JourneySectionComponent, false);
    const journey = fixture.componentInstance;
    expect(journey.current()).toBe(0);
    await tick(fixture, 3200);
    expect(journey.current()).toBe(1);
    const stage = fixture.nativeElement.querySelectorAll('.jst')[5] as HTMLButtonElement;
    stage.click();
    expect(journey.current()).toBe(5);
    await tick(fixture, 10000);
    expect(journey.current()).toBe(5);
  });

  it('pricing cycles clinic sizes until the visitor picks one', async () => {
    const fixture = await mount(PricingSectionComponent, false);
    const pricing = fixture.componentInstance;
    await tick(fixture, 2400);
    expect(pricing.growIndex()).toBe(2);
    await tick(fixture, 2400);
    expect(pricing.growIndex()).toBe(3);
    await tick(fixture, 2400);
    expect(pricing.growIndex()).toBe(0);
    pricing['chooseSize'](1);
    await tick(fixture, 10000);
    expect(pricing.growIndex()).toBe(1);
  });
});
