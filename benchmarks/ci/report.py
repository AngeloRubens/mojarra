"""Renders a JMH JSON result file as a Markdown table (for $GITHUB_STEP_SUMMARY)."""
import json
import sys

rows = json.load(open(sys.argv[1]))
print("| Benchmark | Params | Score | Error (99.9%) | Unit |")
print("|---|---|---:|---:|---|")
for r in rows:
    name = r["benchmark"].rsplit(".", 2)
    bench = name[-2] + "." + name[-1]
    params = ", ".join(f"{k}={v}" for k, v in (r.get("params") or {}).items())
    m = r["primaryMetric"]
    err = m.get("scoreError")
    err = "" if err in (None, "NaN") else f"± {float(err):,.1f}"
    print(f"| {bench} | {params} | {m['score']:,.1f} | {err} | {m['scoreUnit']} |")
