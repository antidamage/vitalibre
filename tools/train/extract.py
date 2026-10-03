#!/usr/bin/env python3
"""Read the raw BP datasets and write plain segment files for the Kotlin trainer (android/trainer).

Each output line (tab separated): subject, dataset, segment, age, sex(M/F), sbp, dbp, fs, then the
waveform as space separated numbers, oriented so that a systolic upstroke is UPWARD (the sign is decided
per dataset from the median skewness of the 0.5-5 Hz band-passed signal; a PPG pulse rises fast and
falls slowly, so its skewness is positive in that orientation). Nothing here computes a feature.

Usage: python extract.py <work_dir>   (the directory the datasets were unpacked into; segments are written under it)
"""
import os, re, sys
import numpy as np
import pandas as pd
from scipy import signal as sg
import scipy.io as sio
sys.path.insert(0, os.path.dirname(__file__))
from xl import read_xlsx

if len(sys.argv) < 2:
    sys.exit(__doc__)
W = sys.argv[1]
OUT = os.path.join(W, 'segments'); os.makedirs(OUT, exist_ok=True)


def skew(x):
    x = x - x.mean(); s = x.std()
    return float((x ** 3).mean() / s ** 3) if s > 0 else 0.0


def polarity(segs, fs):
    """Median skewness and median log(max rise slope / max fall slope) of the 0.5-5 Hz band-passed signal."""
    sos = sg.butter(2, [0.5, 5.0], btype='band', fs=fs, output='sos')
    sk, sl = [], []
    for v in segs:
        n = min(int(20 * fs), len(v))
        if len(v) >= 200 and np.std(v) > 0:
            y = sg.sosfiltfilt(sos, v[:n].astype(float))
            sk.append(skew(y))
            d = np.diff(y)
            sl.append(float(np.log(max(d.max(), 1e-12) / max(-d.min(), 1e-12))))
    return (float(np.median(sk)) if sk else 0.0), (float(np.median(sl)) if sl else 0.0), len(sk)


def write(name, rows, fs, force=None):
    m, sl, n = polarity([r['v'] for r in rows], fs)
    # A pulse is positively skewed when systole is upward; the slope ratio is only a cross-check.
    sign = force if force is not None else (1.0 if m >= 0 else -1.0)
    print(f'{name}: {len(rows)} segments, {len({r["subject"] for r in rows})} subjects, fs={fs} Hz, '
          f'as-recorded median skewness {m:+.2f}, median log(rise/fall slope) {sl:+.2f} over {n} -> sign {sign:+.0f}'
          f'{" (forced by BUT_SIGN)" if force is not None else ""}')
    with open(os.path.join(OUT, f'{name}.tsv'), 'w') as f:
        for r in rows:
            v = sign * r['v'].astype(float)
            f.write('\t'.join([r['subject'], name, r['segment'], str(r['age']), r['sex'], f'{r["sbp"]:.2f}',
                               f'{r["dbp"]:.2f}', str(fs), ' '.join(f'{x:.6g}' for x in v)]) + '\n')


# ---- CP-PPG: 400 Hz (dataset README.txt), red 660 nm, six contact pressures, 142 subjects
cpd = os.path.join(W, 'cp', 'Contact Pressure-PPG (CP-PPG) Dataset')
labels = {}
for r in read_xlsx(os.path.join(cpd, 'CP-PPG Dataset.xlsx'))[2:]:
    try:
        labels[r['A']] = dict(sex='M' if r['B'] == '1' else 'F', age=int(float(r['C'])), h=float(r['D']),
                              w=float(r['E']), sbp=float(r['F']), dbp=float(r['G']))
    except (KeyError, ValueError):
        pass
rows, bad = [], []
for sid, L in labels.items():
    p = os.path.join(cpd, 'csv_form', sid + '.csv')
    if not os.path.exists(p): bad.append((sid, 'no csv')); continue
    a = pd.read_csv(p) if os.path.getsize(p) > 1000 else None
    if a is None or a.shape[1] < 18: bad.append((sid, 'empty file')); continue
    for k, lvl in enumerate((30, 40, 50, 60, 70, 80)):
        v = a.iloc[:, 3 * k].dropna().to_numpy()  # columns end at different lengths; the tail is blank
        if len(v) >= 400 * 10 and np.std(v) > 0:
            rows.append(dict(subject=sid, segment=f'cp{lvl}', v=v, **{k2: L[k2] for k2 in ('age', 'sex', 'sbp', 'dbp')}))
print('CP-PPG skipped subjects:', bad)
write('cp-ppg', rows, 400)

# ---- BUT PPG: 30 Hz camera; the .hea lists the 300 samples as per-"signal" gains, value = 32767 / gain
bd = next(os.path.join(W, 'but', d) for d in os.listdir(os.path.join(W, 'but')))
rows = []
info = np.genfromtxt(os.path.join(bd, 'subject-info.csv'), delimiter=',', dtype=str, skip_header=1, encoding='utf-8-sig')
nolabel = 0
for r in info:
    rid, sex, age, _, _, finger, motion, bp = r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7]
    if motion != '0': continue  # at rest; both Ear/finger codes kept and tagged (which code is the finger is not verified)
    m = re.match(r'^(\d+)/(\d+)$', bp.strip())
    if not m or not age: nolabel += 1; continue
    hea = os.path.join(bd, rid, rid + '_PPG.hea')
    if not os.path.exists(hea): continue
    head = open(hea).read().splitlines()
    if not head[0].startswith(rid + '_PPG 3 '): continue  # a few records carry a malformed header with no channels
    gain, base = re.match(r'\S+ 16 (-?[\d.]+)\((-?\d+)\)', head[2]).groups()  # line 3 is PPG_G
    adc = np.fromfile(os.path.join(bd, rid, rid + '_PPG.dat'), '<i2')[1::3]
    v = (adc - float(base)) / float(gain)
    if len(v) < 250 or np.std(v) == 0: continue
    rows.append(dict(subject=rid[:3], segment=f'L{finger}_{rid}', v=v, age=int(age), sex=sex, sbp=float(m.group(1)), dbp=float(m.group(2))))
print('BUT finger+rest records without BP/age:', nolabel)
write('but-ppg', rows, 30, force=float(os.environ['BUT_SIGN']) if 'BUT_SIGN' in os.environ else None)

# ---- 4-wavelength: 200 Hz, channel 1 (660 nm), 60 s; overlap with CP-PPG decided by age/height/weight/SBP
base = os.path.join(W, '4wl', 'Blood Pressure Measurement based on Four-wavelength PPG Signals')
rows, dups = [], []
for r in read_xlsx(os.path.join(base, 'Subjects Information.xlsx'))[1:]:
    try:
        sid = r['A']; sbp = float(r['B']); dbp = float(r['C']); age = int(float(r['D']))
        h = float(r['E']); w = float(r['F']); sex = 'M' if r['G'].lower().startswith('m') else 'F'
    except (KeyError, ValueError):
        continue
    for cid, L in labels.items():
        if L['age'] == age and abs(L['h'] - h) <= 1 and abs(L['w'] - w) <= 1 and abs(L['sbp'] - sbp) <= 1:
            dups.append((sid, cid)); break
    p = os.path.join(base, 'ppg_data', sid + '.mat')
    if not os.path.exists(p): continue
    mm = sio.loadmat(p)
    d = mm['data'] if 'data' in mm else mm['PPGdata'][0, 0]['data']
    d = np.hstack([np.asarray(d[c][0, 0], float) for c in d.dtype.names]) if d.dtype.names else d
    if d.shape[0] >= 2000 and np.std(d[:, 0]) > 0:
        rows.append(dict(subject=sid, segment='660nm', v=d[:, 0], age=age, sex=sex, sbp=sbp, dbp=dbp))
write('4wl-bp', rows, 200)
with open(os.path.join(OUT, '4wl_overlap_with_cp.csv'), 'w') as f:
    f.write('4wl_id,cp_id\n' + ''.join(f'{a},{b}\n' for a, b in dups))
print('4wl subjects that match a CP-PPG subject:', len(dups))
