import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { CONFIG_SECTIONS, configSectionByKey } from '../config-sections';
import { ModulesView } from '../modules/modules.model';
import { IpdAccess } from './ipd-access';

const view = (state: 'off' | 'trial' | 'on' | 'readonly'): ModulesView => ({
  plan: 'PRO',
  ipd: { state, requested: false },
  hra: { on: false },
  ipdTrialAvailable: false,
  menuCounts: { simple: 7, advanced: 14 },
});

describe('IpdAccess', () => {
  let access: IpdAccess;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    access = TestBed.inject(IpdAccess);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('declares the IPD sections in their own group', () => {
    const ipd = CONFIG_SECTIONS.filter((s) => s.requires === 'ipd');
    expect(ipd.map((s) => s.key)).toEqual(['ipbeds', 'iprates', 'ipadm', 'iper', 'ipdis']);
    expect(configSectionByKey('ipadm')?.label).toBe('Admission and deposits');
    expect(ipd.every((s) => s.group === 'In-patients (IPD)')).toBe(true);
    expect(configSectionByKey('ipbeds')?.label).toBe('Wards, rooms and beds');
    expect(configSectionByKey('iprates')?.label).toBe('Bed categories and charges');
  });

  it('hides the IPD sections until the state is trial, on or read-only', () => {
    const beds = configSectionByKey('ipbeds')!;
    const profile = configSectionByKey('profile')!;
    expect(access.visible(beds)).toBe(false);
    expect(access.visible(profile)).toBe(true);
    access.set(view('off'));
    expect(access.visible(beds)).toBe(false);
    for (const state of ['trial', 'on', 'readonly'] as const) {
      access.set(view(state));
      expect(access.visible(beds)).toBe(true);
    }
  });

  it('loads the state once', () => {
    access.ensureLoaded();
    access.ensureLoaded();
    http.expectOne('/api/v1/platform/modules').flush(view('trial'));
    expect(access.state()).toBe('trial');
    access.ensureLoaded();
  });

  it('keeps the sections hidden and flags a failure when loading fails', () => {
    access.ensureLoaded();
    http.expectOne('/api/v1/platform/modules').flush('x', { status: 500, statusText: 'Error' });
    expect(access.failed()).toBe(true);
    expect(access.available()).toBe(false);
  });
});
