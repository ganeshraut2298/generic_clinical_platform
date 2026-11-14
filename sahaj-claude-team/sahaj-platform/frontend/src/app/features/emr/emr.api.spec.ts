import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { EmrApi } from './emr.api';

const EMR = '/api/v1/opd/emr';

/** The EMR api calls exactly the paths, methods and bodies of docs/api/opd.yaml 0.3.0. */
describe('EmrApi', () => {
  let api: EmrApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(EmrApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function expectCall(method: string, url: string, body?: unknown) {
    const req = http.expectOne(url);
    expect(req.request.method).toBe(method);
    if (body !== undefined) {
      expect(req.request.body).toEqual(body);
    }
    req.flush({});
    return req;
  }

  describe('configuration (SAH-45)', () => {
    it('reads the departments and one department', () => {
      api.listDepartments().subscribe();
      expectCall('GET', `${EMR}/departments`);
      api.getDepartment('gm').subscribe();
      expectCall('GET', `${EMR}/departments/gm`);
    });

    it('saves a draft with PUT and publishes with POST, sending the same body', () => {
      const body = { revision: 2, origin: 'EDIT' as const, sections: [] };
      api.saveDraft('dental', body).subscribe();
      expectCall('PUT', `${EMR}/departments/dental/draft`, body);
      api.publish('dental', body).subscribe();
      expectCall('POST', `${EMR}/departments/dental/publish`, body);
    });

    it('reads the reset target', () => {
      api.getResetTarget('og').subscribe();
      expectCall('GET', `${EMR}/departments/og/reset-target`);
    });
  });

  describe('wizard (SAH-54)', () => {
    it('reads the starters, one starter and the doctor options', () => {
      api.listStarters().subscribe();
      expectCall('GET', `${EMR}/starter-departments`);
      api.getStarter('physio').subscribe();
      expectCall('GET', `${EMR}/starter-departments/physio`);
      api.listDoctorOptions().subscribe();
      expectCall('GET', `${EMR}/department-doctor-options`);
    });

    it('creates a department with POST', () => {
      const body = {
        name: 'Dental chair 2',
        source: { kind: 'COPY' as const, copyFromDepartmentKey: 'dental' },
        copyPrintFlags: true,
        doctorMembershipIds: [],
        rooms: ['Chair 2'],
      };
      api.createDepartment(body).subscribe();
      expectCall('POST', `${EMR}/departments`, body);
    });
  });

  describe('record (SAH-46, SAH-47, SAH-52)', () => {
    it('reads the doctor context', () => {
      api.getDoctorContext().subscribe();
      expectCall('GET', `${EMR}/me`);
    });

    it('opens a record for an encounter', () => {
      api.openRecord({ encounterType: 'IPD_ROUND', encounterId: 'a1' }).subscribe();
      expectCall('POST', `${EMR}/records/open`, { encounterType: 'IPD_ROUND', encounterId: 'a1' });
    });

    it('reads a record and saves its draft', () => {
      api.getRecord('r1').subscribe();
      expectCall('GET', `${EMR}/records/r1`);
      const body = { revision: 3, uiState: { part: 'ASSESSMENT' as const, sectionKey: null }, sections: [] };
      api.saveRecordDraft('r1', body).subscribe();
      expectCall('PUT', `${EMR}/records/r1/draft`, body);
    });

    it('switches the department and advises admission', () => {
      api.switchDepartment('r1', { departmentKey: 'dental', revision: 3 }).subscribe();
      expectCall('PUT', `${EMR}/records/r1/department`, { departmentKey: 'dental', revision: 3 });
      api.adviseAdmission('r1', { revision: 4, referralLine: 'Refer to a surgeon' }).subscribe();
      expectCall('POST', `${EMR}/records/r1/admission-advice`, { revision: 4, referralLine: 'Refer to a surgeon' });
    });

    it('changes the visit type with PATCH on the visit', () => {
      api.changeVisitType('v1', 'REVISIT').subscribe();
      expectCall('PATCH', '/api/v1/opd/visits/v1/visit-type', { visitType: 'REVISIT' });
    });
  });

  describe('lookups', () => {
    it('searches a master with the text and the starter key', () => {
      api.searchMaster('complaints', 'fev', 'gm').subscribe();
      const req = http.expectOne((r) => r.url === `${EMR}/masters/complaints`);
      expect(req.request.params.get('q')).toBe('fev');
      expect(req.request.params.get('specialty')).toBe('gm');
      req.flush({});
    });

    it('asks for the doctor quick options with a limit', () => {
      api.quickOptions('diagnoses', null, 10).subscribe();
      const req = http.expectOne((r) => r.url === `${EMR}/quick-options/diagnoses`);
      expect(req.request.params.get('limit')).toBe('10');
      expect(req.request.params.has('specialty')).toBe(false);
      req.flush({});
    });

    it('searches diagnoses, medicines and the active services', () => {
      api.searchDiagnoses('fever', 'gm').subscribe();
      const d = http.expectOne((r) => r.url === '/api/v1/opd/diagnoses');
      expect(d.request.params.get('q')).toBe('fever');
      d.flush([]);
      api.searchMedicines('para').subscribe();
      http.expectOne((r) => r.url === '/api/v1/opd/medicines' && r.params.get('q') === 'para').flush({});
      api.activeServices().subscribe();
      http.expectOne((r) => r.url === '/api/v1/opd/services' && r.params.get('active') === 'true').flush([]);
    });
  });
});
