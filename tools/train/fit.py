#!/usr/bin/env python3
"""Fit and judge a trained blood-pressure model against the app's age/sex prior.

Inputs: feature CSVs written by the Kotlin trainer (android/trainer), one per dataset.
  python fit.py <cp-ppg.csv> <but-ppg.csv> [4wl-bp.csv] [--overlap 4wl_overlap_with_cp.csv] [--out tools/train/out]

Decision rule (fixed before looking at results): the trained model ships only if BOTH the CP-PPG subject-wise
CV systolic MAE AND the BUT PPG held-out systolic MAE (trained on CP-PPG, all 38 labelled at-rest subjects) beat
the age/sex prior by at least 1 mmHg. Nothing is tuned to pass: ridge alpha, windows and caps are set below.
"""
import json, math, os, sys
import numpy as np
import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
FEATS = ['heartRate', 'intervalCV', 'crestFraction', 'skewness', 'reflectionIndex']
ALPHA = 10.0          # ridge strength, in units where each subject's weights sum to 1
REPEATS, FOLDS = 5, 5
MIN_GAIN = 1.0        # mmHg of systolic MAE the trained model must beat the prior by
PRIOR = json.load(open(os.path.join(HERE, '..', '..', 'App', 'Resources', 'bp-model.json')))


def baseline(age, male, m=PRIOR):
    """BPEstimator.baseline: age clamped to 18-90, sex offset +/- maleOffset."""
    yrs = np.clip(age, 18, 90) - m['referenceAge']
    sgn = np.where(male == 1, 1.0, -1.0)
    return (m['baseSystolic'] + yrs * m['ageSystolicPerYear'] + sgn * m['maleSystolicOffset'],
            m['baseDiastolic'] + yrs * m['ageDiastolicPerYear'] + sgn * m['maleDiastolicOffset'])


def prior_full(df, m=PRIOR):
    """The app's whole v1 model: baseline plus its capped feature terms (BPEstimator.raw)."""
    s, d = baseline(df.age.values, df.male.values, m)
    ds = np.zeros(len(df)); dd = np.zeros(len(df))
    for t in m['terms']:
        z = (df[t['feature']].values - t['mean']) / t['scale']
        ds += z * t['systolic']; dd += z * t['diastolic']
    return (s + np.clip(ds, -m['maxSystolicAdjust'], m['maxSystolicAdjust']),
            d + np.clip(dd, -m['maxDiastolicAdjust'], m['maxDiastolicAdjust']))


def load(path):
    df = pd.read_csv(path)
    df['male'] = (df.sex == 'M').astype(int)
    df['subject_id'] = df.subject_id.astype(str)
    return df


class Fit:
    """Subject-weighted ridge on z-scored features, with age and sex entered the way the app's baseline does."""
    def __init__(self, df, feats=FEATS):
        self.feats = feats
        if feats:
            self.mu = df[feats].mean().values; self.sd = df[feats].std().values + 1e-9
        else:
            self.mu = np.zeros(0); self.sd = np.ones(0)
        w = 1.0 / df.groupby('subject_id').subject_id.transform('count').values
        Z = self._z(df)
        A = np.column_stack([np.ones(len(df)), df.age.values - 30.0, np.where(df.male.values == 1, 1.0, -1.0), Z])
        P = np.eye(A.shape[1]) * ALPHA
        P[0, 0] = P[1, 1] = P[2, 2] = 0   # intercept, age slope and sex offset are unpenalised
        self.coef = {}
        for comp in ('sbp', 'dbp'):
            self.coef[comp] = np.linalg.solve(A.T @ (A * w[:, None]) + P, A.T @ (w * df[comp].values))
        self.cap = {c: (float(np.percentile(np.abs(Z @ self.coef[c][3:]), 95)) if feats else 0.0) for c in self.coef}

    def _z(self, df):
        return (df[self.feats].values - self.mu) / self.sd if self.feats else np.zeros((len(df), 0))

    def predict(self, df):
        Z = self._z(df)
        out = []
        for comp in ('sbp', 'dbp'):
            c = self.coef[comp]; age = np.clip(df.age.values, 18, 90)
            base = c[0] + c[1] * (age - 30.0) + c[2] * np.where(df.male.values == 1, 1.0, -1.0)
            out.append(base + (np.clip(Z @ c[3:], -self.cap[comp], self.cap[comp]) if self.feats else 0.0))
        return out


def mean_baseline(train, df):
    # per-subject mean of the training set, so a subject with many windows does not dominate
    m = train.groupby('subject_id')[['sbp', 'dbp']].first().mean()
    return np.full(len(df), m.sbp), np.full(len(df), m.dbp)


def metrics(df, ps, pd_):
    """Subject-weighted MAE, mean error (pred - true) and SD of error per component, and r at subject level."""
    out = {}
    for comp, p in (('sbp', ps), ('dbp', pd_)):
        e = p - df[comp].values
        t = pd.DataFrame({'s': df.subject_id.values, 'e': e, 'ae': np.abs(e), 'e2': e ** 2, 'p': p, 'y': df[comp].values})
        g = t.groupby('s')
        sub = pd.DataFrame({'ae': g.ae.mean(), 'e': g.e.mean(), 'e2': g.e2.mean(), 'p': g.p.mean(), 'y': g.y.first()})
        me = sub.e.mean()
        sd = math.sqrt(max(0.0, sub.e2.mean() - me ** 2))
        r = float(np.corrcoef(sub.p, sub.y)[0, 1]) if sub.p.std() > 1e-9 else float('nan')
        out[comp] = dict(mae=float(sub.ae.mean()), me=float(me), sd=sd, r=r, per_subject_ae=sub.ae)
    return out


def methods(train, test):
    f = Fit(train)
    fa = Fit(train, feats=[])   # age and sex only, refitted: separates "recalibration" from "pulse features"
    return {'mean-of-training': mean_baseline(train, test),
            'prior age/sex (b)': baseline(test.age.values, test.male.values),
            'prior-1 full (incl. v1 terms)': prior_full(test),
            'age/sex refit (no features)': fa.predict(test),
            'trained (v2 candidate)': f.predict(test)}, f


def cv(df):
    subs = df.subject_id.unique()
    per = {}
    first = None
    for rep in range(REPEATS):
        rng = np.random.default_rng(1000 + rep)
        order = rng.permutation(subs); fold_of = {s: i % FOLDS for i, s in enumerate(order)}
        fold = df.subject_id.map(fold_of).values
        preds = {}
        for k in range(FOLDS):
            tr, te = df[fold != k], df[fold == k]
            ms, _ = methods(tr, te)
            for name, (ps, pdd) in ms.items():
                a = preds.setdefault(name, [np.zeros(len(df)), np.zeros(len(df))])
                a[0][fold == k] = ps; a[1][fold == k] = pdd
        for name, (ps, pdd) in preds.items():
            per.setdefault(name, []).append(metrics(df, ps, pdd))
        if rep == 0:
            first = preds
    return per, first


def fmt_cv(per):
    lines = []
    for name, ms in per.items():
        row = [f'{name:32s}']
        for comp in ('sbp', 'dbp'):
            mae = np.array([m[comp]['mae'] for m in ms])
            row.append(f"{comp} MAE {mae.mean():.2f} (SE {mae.std(ddof=1) / math.sqrt(len(mae)):.2f}), "
                       f"ME {np.mean([m[comp]['me'] for m in ms]):+.2f} +/- {np.mean([m[comp]['sd'] for m in ms]):.2f}, "
                       f"r {np.nanmean([m[comp]['r'] for m in ms]):+.2f}")
        lines.append('  ' + ' | '.join(row))
    return '\n'.join(lines)


def boot_gain(df, pa, pb, comp='sbp', n=2000):
    """Subject-level bootstrap of MAE(a) - MAE(b): positive means b is better."""
    ma = metrics(df, pa[0], pa[1])[comp]['per_subject_ae']; mb = metrics(df, pb[0], pb[1])[comp]['per_subject_ae']
    d = (ma - mb).values; rng = np.random.default_rng(7)
    bs = [d[rng.integers(0, len(d), len(d))].mean() for _ in range(n)]
    return float(d.mean()), float(np.percentile(bs, 2.5)), float(np.percentile(bs, 97.5))


def report_test(name, train, test):
    ms, f = methods(train, test)
    res = {k: metrics(test, *v) for k, v in ms.items()}
    print(f'\n{name}: {test.subject_id.nunique()} subjects, {len(test)} windows')
    for k, m in res.items():
        print(f"  {k:32s} sbp MAE {m['sbp']['mae']:.2f} ME {m['sbp']['me']:+.2f} +/- {m['sbp']['sd']:.2f} r {m['sbp']['r']:+.2f} | "
              f"dbp MAE {m['dbp']['mae']:.2f} ME {m['dbp']['me']:+.2f} +/- {m['dbp']['sd']:.2f} r {m['dbp']['r']:+.2f}")
    g = boot_gain(test, ms['prior age/sex (b)'], ms['trained (v2 candidate)'])
    print(f'  systolic MAE gain, prior(b) - trained: {g[0]:+.2f} mmHg (subject bootstrap 95% CI {g[1]:+.2f} to {g[2]:+.2f})')
    return res, g, f


def main():
    a = [x for x in sys.argv[1:] if not x.startswith('--')]
    opt = {sys.argv[i]: sys.argv[i + 1] for i in range(1, len(sys.argv) - 1) if sys.argv[i].startswith('--')}
    a = [x for x in a if x not in opt.values()]
    out = opt.get('--out', os.path.join(HERE, 'out')); os.makedirs(out, exist_ok=True)
    cp, but = load(a[0]), load(a[1])
    print(f'CP-PPG windows {len(cp)} from {cp.subject_id.nunique()} subjects; BUT windows {len(but)} from {but.subject_id.nunique()} subjects')
    print(f'prior (b): sbp {PRIOR["baseSystolic"]} age {PRIOR["ageSystolicPerYear"]}/yr male +/-{PRIOR["maleSystolicOffset"]}; '
          f'dbp {PRIOR["baseDiastolic"]} age {PRIOR["ageDiastolicPerYear"]}/yr male +/-{PRIOR["maleDiastolicOffset"]}')
    for nm, d in (('CP-PPG', cp), ('BUT', but)):
        s = d.groupby('subject_id')[['sbp', 'dbp', 'age']].first()
        print(f'{nm} subject labels: sbp {s.sbp.mean():.1f} sd {s.sbp.std():.1f}, dbp {s.dbp.mean():.1f} sd {s.dbp.std():.1f}, '
              f'age {s.age.mean():.0f} sd {s.age.std():.0f}')
    print(f'\nCP-PPG subject-wise {FOLDS}-fold CV x {REPEATS} shuffles (SE = SD of the {REPEATS} repeat MAEs / sqrt({REPEATS}); split noise only)')
    per, first = cv(cp)
    print(fmt_cv(per))
    g = boot_gain(cp, first['prior age/sex (b)'], first['trained (v2 candidate)'])
    print(f'  systolic MAE gain, prior(b) - trained, repeat 1: {g[0]:+.2f} mmHg (subject bootstrap 95% CI {g[1]:+.2f} to {g[2]:+.2f})')
    cv_gain = np.mean([m['sbp']['mae'] for m in per['prior age/sex (b)']]) - np.mean([m['sbp']['mae'] for m in per['trained (v2 candidate)']])

    res, _, final = report_test('BUT PPG held-out, all labelled at-rest subjects (trained on all CP-PPG)', cp, but)
    but_gain = res['prior age/sex (b)']['sbp']['mae'] - res['trained (v2 candidate)']['sbp']['mae']
    for code in ('0', '1'):
        report_test(f'  BUT PPG Ear/finger code {code} only', cp, but[but.segment.str.startswith('L' + code)])
    if len(a) > 2:
        w = load(a[2])
        if '--overlap' in opt:
            ov = set(pd.read_csv(opt['--overlap']).iloc[:, 0].astype(str))
            w = w[~w.subject_id.isin(ov)]
        report_test('4-wavelength (660 nm reflection PPG, 200 Hz), excluding subjects that also appear in CP-PPG', cp, w)

    passed = cv_gain >= MIN_GAIN and but_gain >= MIN_GAIN
    print(f'\nDECISION: CP-PPG CV systolic gain {cv_gain:+.2f}, BUT held-out gain {but_gain:+.2f} mmHg; need >= {MIN_GAIN} on both -> '
          + ('PASS' if passed else 'FAIL, no candidate written'))
    names = ['intercept', 'age/yr', 'male', *FEATS]
    print('final fit on all CP-PPG, systolic:', dict(zip(names, np.round(final.coef['sbp'], 3))))
    print('final fit on all CP-PPG, diastolic:', dict(zip(names, np.round(final.coef['dbp'], 3))))
    print('adjustment caps (95th pct of |feature adjustment|):', {k: round(v, 2) for k, v in final.cap.items()})
    if passed:
        mae = {c: math.ceil(np.mean([m[c]['mae'] for m in per['trained (v2 candidate)']])) for c in ('sbp', 'dbp')}
        cs, cd = final.coef['sbp'], final.coef['dbp']
        model = {'version': '2-trained', 'validated': False,
                 'baseSystolic': round(cs[0], 2), 'baseDiastolic': round(cd[0], 2),
                 'referenceAge': 30, 'ageSystolicPerYear': round(cs[1], 3), 'ageDiastolicPerYear': round(cd[1], 3),
                 'maleSystolicOffset': round(cs[2], 2), 'maleDiastolicOffset': round(cd[2], 2),
                 'terms': [{'feature': f, 'mean': round(float(final.mu[i]), 4), 'scale': round(float(final.sd[i]), 4),
                            'systolic': round(float(cs[3 + i]), 3), 'diastolic': round(float(cd[3 + i]), 3)} for i, f in enumerate(FEATS)],
                 'maxSystolicAdjust': round(final.cap['sbp'], 1), 'maxDiastolicAdjust': round(final.cap['dbp'], 1),
                 'halfWidthSystolic': mae['sbp'], 'halfWidthDiastolic': mae['dbp']}
        json.dump(model, open(os.path.join(out, 'bp-model-v2.json'), 'w'), indent=2)
        print('wrote', os.path.join(out, 'bp-model-v2.json'))


if __name__ == '__main__':
    main()
