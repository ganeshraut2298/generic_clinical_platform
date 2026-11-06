import { profileChip } from './profile-chip';

describe('profileChip', () => {
  it('shows the name over the role when there is a real name', () => {
    expect(profileChip('Dr. Meera Kulkarni', 'Owner')).toEqual({
      primary: 'Dr. Meera Kulkarni',
      secondary: 'Owner',
    });
  });

  it('shows the role once when the name is the placeholder "Owner"', () => {
    expect(profileChip('Owner', 'Owner')).toEqual({ primary: 'Owner', secondary: '' });
  });

  it('compares the name with the role without case or spaces', () => {
    expect(profileChip('  front DESK ', 'Front desk')).toEqual({ primary: 'Front desk', secondary: '' });
  });

  it('shows the role only when the name is missing or blank', () => {
    expect(profileChip(undefined, 'Nurse')).toEqual({ primary: 'Nurse', secondary: '' });
    expect(profileChip('   ', 'Nurse')).toEqual({ primary: 'Nurse', secondary: '' });
  });

  it('shows the name only when the role is not known yet', () => {
    expect(profileChip('Sr. Anjali', '')).toEqual({ primary: 'Sr. Anjali', secondary: '' });
  });

  it('shows nothing when both are missing', () => {
    expect(profileChip('', '')).toEqual({ primary: '', secondary: '' });
  });
});
