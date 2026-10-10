"""Per-request medians (sum over phases of each phase's median) of every scenario, per arm and round, from the
A-1 ... C-2 dumps of perf_compare.sh, with B and C against A on the median of the two rounds' values. Unlike the
averages, a few GC or JIT pauses do not move a median. Usage: python3 medians.py <perf-out dir>"""
import os
import sys

PHASES = {"RESTORE_VIEW", "APPLY_REQUEST_VALUES", "PROCESS_VALIDATIONS", "UPDATE_MODEL_VALUES", "INVOKE_APPLICATION", "RENDER_RESPONSE"}


def read(path):
    """-> {scenario: (sum of p50, sum of p90, sum of min)} or None when the dump has no percentiles"""
    rows = {}
    for line in open(path, encoding="utf-8"):
        parts = line.split()
        if len(parts) < 9 or parts[1] not in PHASES or parts[0] == "<TOTAL>":
            continue
        try:
            lo, p50, p90 = int(parts[5]), int(parts[7]), int(parts[8])
        except ValueError:
            continue
        r = rows.setdefault(parts[0], [0, 0, 0])
        r[0] += p50
        r[1] += p90
        r[2] += lo
    return rows or None


out = sys.argv[1]
dumps = {}
for arm in "ABC":
    for rnd in "12":
        path = os.path.join(out, f"{arm}-{rnd}.txt")
        if os.path.exists(path):
            rows = read(path)
            if rows:
                dumps[arm + rnd] = rows
if "A1" not in dumps:
    sys.exit(0)

cols = [k for k in ("A1", "A2", "B1", "B2", "C1", "C2") if k in dumps]
print("### Per-request median (us, sum of phase medians), each round; deltas on the mean of the two rounds")
print()
print("| scenario | " + " | ".join(cols) + " | B vs A | C vs A | p90 B vs A | p90 C vs A |")
print("|---" * (len(cols) + 5) + "|")
total = {k: 0 for k in cols}


def pair(arm, i, scenario):
    vals = [dumps[arm + r][scenario][i] for r in "12" if arm + r in dumps and scenario in dumps[arm + r]]
    return sum(vals) / len(vals) if vals else None


def delta(a, b):
    return "-" if a is None or b is None or not b else f"{(a - b) / b * 100:+.1f}%"


for scenario in sorted(dumps["A1"]):
    cells = []
    for k in cols:
        v = dumps[k].get(scenario)
        cells.append(str(v[0]) if v else "-")
        if v:
            total[k] += v[0]
    a, b, c = pair("A", 0, scenario), pair("B", 0, scenario), pair("C", 0, scenario)
    a9, b9, c9 = pair("A", 1, scenario), pair("B", 1, scenario), pair("C", 1, scenario)
    print(f"| {scenario} | " + " | ".join(cells) + f" | {delta(b, a)} | {delta(c, a)} | {delta(b9, a9)} | {delta(c9, a9)} |")
ta = (total.get("A1", 0) + total.get("A2", 0)) / 2
tb = (total.get("B1", 0) + total.get("B2", 0)) / 2
tc = (total.get("C1", 0) + total.get("C2", 0)) / 2
print(f"| **TOTAL** | " + " | ".join(str(total[k]) for k in cols) + f" | {delta(tb, ta)} | {delta(tc, ta) if tc else '-'} | | |")
