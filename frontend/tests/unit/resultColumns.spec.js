import { hasRenamedColumns } from '../../src/utils/resultColumns';

describe('result column names given out by the platform', () => {
  it('treats a result without the flags as untouched', () => {
    expect(hasRenamedColumns(undefined)).toBe(false);
    expect(hasRenamedColumns(null)).toBe(false);
    expect(hasRenamedColumns([])).toBe(false);
    // An older backend does not send the flags at all.
    expect(hasRenamedColumns('id')).toBe(false);
  });

  it('accepts a result in which every column named itself', () => {
    expect(hasRenamedColumns([false, false, false])).toBe(false);
  });

  it('refuses a result carrying one numbered name', () => {
    expect(hasRenamedColumns([false, true, false])).toBe(true);
    expect(hasRenamedColumns([true])).toBe(true);
  });

  it('reads the flags by position, whatever else they hold', () => {
    expect(hasRenamedColumns([false, null, false])).toBe(false);
    expect(hasRenamedColumns([false, 1])).toBe(true);
  });
});
