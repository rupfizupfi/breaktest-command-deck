#!/usr/bin/env python3
"""Derives the SpecimenProfile constants from the bench's recorded force traces.

    python script/derive-sim-profiles.py [bench-results]

Reads every <run-id>/<millis>_force.csv (epoch millis, newton) in a copy of the bench's
~/breaktester directory — gitignored, and not needed to run the simulator — keeps the runs that
loaded and broke cleanly, classifies them and prints the numbers SpecimenProfile.java carries.
Standard library only. Method and caveats: doc/dev/backend/specimen-profiles.md.
"""
import glob
import os
import statistics as st
import sys

# Simulated crosshead travel per second at speed 350: the deck turns speed into rpm by dividing by
# 0.375 and the simulated drive moves 0.375 mm per revolution, so travel is speed/60 mm/s. 350 is
# the only speed the result export records; every run is assumed to have used it.
MM_PER_SECOND = 350 / 60.0

# Recordings left open across runs (one stream written into several hour-long files) and the
# sensor's pre-2024-11 era of ~1 kN spikes, whose noise is not representative.
OVERLAPPING_RUNS = {5, 6, 9, 11, 15, 18}
CLEAN_SENSOR_FROM_RUN = 121

# Runs the result export names (Polyamid webbing, Spider Nylove and Slacktivity Pinktube).
POLYAMIDE_INTACT = set(range(113, 120)) | set(range(143, 147))
POLYAMIDE_DEGRADED = set(range(98, 110)) | set(range(140, 143))

KNOTS = [i / 10 for i in range(11)]


def load(path):
    t, f = [], []
    with open(path) as fh:
        for line in fh:
            try:
                a, b = line.split(',')
                t.append(int(a) / 1000)
                f.append(float(b))
            except ValueError:
                pass
    return t, f


def analyse(run_id, path):
    t, f = load(path)
    if len(f) < 300 or t[-1] - t[0] > 200:
        return None
    peak = max(f)
    # Below 4 kN nothing broke; above 80 kN is a garbage sample (runs 98, 99, 238 carry 1e7..9e7 N).
    if not 4000 <= peak <= 80000:
        return None
    last_high = max(k for k in range(len(f)) if f[k] > 0.5 * peak)
    after = f[last_high + 1:last_high + 40]
    # A break, not a shut-off on the upper threshold or an operator stop at load.
    if not after or min(after) > 0.3 * peak:
        return None
    duration = t[last_high] - t[0]
    if duration < 4:
        return None

    pretension = st.median(f[:15])
    shape = []
    for x in KNOTS:
        target = t[0] + x * duration
        i = min(range(last_high + 1), key=lambda k: abs(t[k] - target))
        shape.append((st.median(f[max(0, i - 5):i + 6]) - pretension) / (peak - pretension))
    shape[0], shape[-1] = 0.0, 1.0

    j = last_high
    while j < len(f) and f[j] > 0.1 * peak:
        j += 1
    # Most recordings stop at the first sample under the lower threshold; only some show the settle.
    residual = st.median(f[j:j + 60]) if j < len(f) - 3 else None

    mid = range(max(5, last_high // 3), min(last_high // 3 + 300, last_high))
    noise = st.pstdev([f[k] - st.median(f[k - 4:k + 5]) for k in mid]) if len(mid) > 10 else None

    return dict(id=run_id, pretension=pretension, peak=peak, seconds=duration,
                extension=MM_PER_SECOND * duration, shape=shape, residual=residual, noise=noise)


def behaviour_class(run):
    if run['peak'] < 9000:
        return 'degraded'
    if run['seconds'] >= 35:
        return 'compliant'
    return 'stiff' if run['peak'] >= 25000 else 'stiff-low-strength'


PROFILES = {
    'COMPLIANT': lambda r: behaviour_class(r) == 'compliant',
    'STIFF': lambda r: behaviour_class(r) == 'stiff',
    'STIFF_LOW_STRENGTH': lambda r: behaviour_class(r) == 'stiff-low-strength',
    'POLYAMIDE_WEBBING': lambda r: r['id'] in POLYAMIDE_INTACT and behaviour_class(r) == 'compliant',
    'POLYAMIDE_WEBBING_DEGRADED': lambda r: r['id'] in POLYAMIDE_DEGRADED and behaviour_class(r) == 'degraded',
}


def batch_spread(runs, key):
    """Median coefficient of variation inside batches: consecutive ids, peaks within 15 %."""
    batches, current = [], [runs[0]]
    for a, b in zip(runs, runs[1:]):
        if b['id'] - a['id'] <= 2 and abs(b['peak'] / a['peak'] - 1) < 0.15:
            current.append(b)
        else:
            batches.append(current)
            current = [b]
    batches.append(current)
    cvs = [st.pstdev([r[key] for r in b]) / st.mean([r[key] for r in b]) for b in batches if len(b) >= 3]
    return (st.median(cvs) if cvs else float('nan')), len(cvs)


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else 'bench-results'
    runs = []
    for path in glob.glob(os.path.join(root, '*', '*_force.csv')):
        run_dir = os.path.basename(os.path.dirname(path))
        if not run_dir.isdigit() or int(run_dir) in OVERLAPPING_RUNS:
            continue
        run = analyse(int(run_dir), path)
        if run:
            runs.append(run)
    runs.sort(key=lambda r: r['id'])
    print(f'{len(runs)} clean breaks')

    clean_noise = [r['noise'] for r in runs if r['id'] >= CLEAN_SENSOR_FROM_RUN and r['noise'] is not None]
    print(f'sensor noise (runs >= {CLEAN_SENSOR_FROM_RUN}): {st.median(clean_noise):.1f} N')
    # Bench-wide, not per profile: a recording only shows the settle when the residual stayed above
    # the lower threshold, so any small group's median is biased upward.
    settled = [r['residual'] for r in runs if r['residual'] is not None]
    print(f'residual after break: {st.median(settled):.0f} N over {len(settled)} runs that show it')

    for name, selected in PROFILES.items():
        group = [r for r in runs if selected(r)]
        shape = [st.median(r['shape'][i] for r in group) for i in range(len(KNOTS))]
        for i in range(1, len(shape)):
            shape[i] = max(shape[i], shape[i - 1])
        force_cv, batches = batch_spread(group, 'peak')
        extension_cv, _ = batch_spread(group, 'extension')
        print(f"\n{name}: {len(group)} runs, {batches} batches, ids {[r['id'] for r in group]}")
        print(f"  pretension {st.median(r['pretension'] for r in group):.0f} N"
              f"  break {st.median(r['peak'] for r in group):.0f} N (cv {force_cv:.3f})"
              f"  at {st.median(r['extension'] for r in group):.0f} mm (cv {extension_cv:.3f})"
              f"  = {st.median(r['seconds'] for r in group):.1f} s")
        print('  shape ' + ', '.join(f'{s:.3f}' for s in shape[1:-1]))


if __name__ == '__main__':
    main()
