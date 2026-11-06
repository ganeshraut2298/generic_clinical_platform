import { DEFAULT_WORKFLOW_STEPS, WorkflowStep } from '../workflow/workflow.model';
import { boardColumns, currentStages } from './queue-columns.util';
import { Visit, VisitStage } from './queue.model';

function visit(stage: VisitStage, id: string = stage): Visit {
  return {
    id,
    tokenNo: 1,
    patientId: 'p',
    patientName: 'A',
    age: 30,
    sex: 'M',
    doctorMembershipId: 'd',
    doctorName: 'Dr',
    visitType: 'NEW',
    stage,
    feePaise: 0,
    workflowVersionNo: 1,
    createdAt: '2026-11-02T04:00:00Z',
  };
}

function withSteps(overrides: Partial<Record<WorkflowStep['key'], boolean>>): WorkflowStep[] {
  return DEFAULT_WORKFLOW_STEPS.map((s) => ({ ...s, on: overrides[s.key] ?? s.on }));
}

const labels = (steps: WorkflowStep[], visits: Visit[] = []) => boardColumns(steps, visits).map((c) => c.label);

describe('boardColumns', () => {
  it('uses the default steps: Waiting, Vitals, With doctor, Billing, Done', () => {
    expect(labels([...DEFAULT_WORKFLOW_STEPS])).toEqual(['Waiting', 'Vitals', 'With doctor', 'Billing', 'Done']);
  });

  it('drops Vitals when it is off and adds Counselling before Billing when it is on', () => {
    expect(labels(withSteps({ vitals: false, counsel: true }))).toEqual([
      'Waiting',
      'With doctor',
      'Counselling',
      'Billing',
      'Done',
    ]);
  });

  it('ignores steps that are not stages (token, case paper, follow-up)', () => {
    expect(currentStages(withSteps({ casepaper: false, followup: false }))).toEqual([
      'WAITING',
      'VITALS',
      'WITH_DOCTOR',
      'BILLING',
    ]);
  });

  it('adds a legacy column for a stage still holding a visit but off in the current version', () => {
    const columns = boardColumns(withSteps({ vitals: false }), [visit('VITALS'), visit('WAITING', 'w')]);
    expect(columns.map((c) => c.label)).toEqual(['Waiting', 'With doctor', 'Billing', 'Vitals', 'Done']);
    expect(columns.find((c) => c.stage === 'VITALS')?.legacy).toBe(true);
    expect(columns.filter((c) => c.legacy)).toHaveLength(1);
  });

  it('adds a legacy column only once, and not for Done visits', () => {
    const columns = boardColumns(withSteps({}), [visit('COUNSEL', 'a'), visit('COUNSEL', 'b'), visit('DONE')]);
    expect(columns.filter((c) => c.legacy).map((c) => c.stage)).toEqual(['COUNSEL']);
  });
});
