"""Summarizes a JFR recording from the test/perf bench as Markdown, attributing samples to Mojarra code.

For every CPU sample (jdk.ExecutionSample) and allocation sample (jdk.ObjectAllocationSample) the stack is walked
from the top: the first Mojarra frame (com.sun.faces / jakarta.faces) is the "entry" the cost is charged to, so time
spent in JDK collections, String, EL, Weld... called from Mojarra lands on the Mojarra method that called it.
Inclusive counts (each Mojarra method once per stack) are reported too.

Usage: python3 jfr_mojarra.py <recording.jfr> [top_n]
"""
import collections
import json
import subprocess
import sys

MOJARRA = ("com.sun.faces.", "jakarta.faces.")
JDK_JFR = "jfr"


def frames(event):
    st = event["values"].get("stackTrace")
    if not st:
        return []
    out = []
    for f in st.get("frames", []):
        m = f["method"]
        out.append(m["type"]["name"] + "." + m["name"])
    return out


def load(path, event_type):
    raw = subprocess.run([JDK_JFR, "print", "--json", "--stack-depth", "96", "--events", event_type, path],
                         check=True, capture_output=True, text=True).stdout
    return json.loads(raw)["recording"]["events"]


def summarize(events, weight_of):
    entry = collections.Counter()
    inclusive = collections.Counter()
    top = collections.Counter()
    total = 0
    for e in events:
        fs = frames(e)
        if not fs:
            continue
        w = weight_of(e)
        total += w
        top[fs[0]] += w
        first = next((f for f in fs if f.startswith(MOJARRA)), None)
        if first is None:
            continue
        entry[first] += w
        for f in set(fs):
            if f.startswith(MOJARRA):
                inclusive[f] += w
    return total, top, entry, inclusive


def table(title, counter, total, n, unit):
    print(f"#### {title}\n")
    print(f"| # | Method | {unit} | % |")
    print("|---:|---|---:|---:|")
    for i, (k, v) in enumerate(counter.most_common(n), 1):
        print(f"| {i} | `{k}` | {v:,.0f} | {100.0 * v / total:.1f}% |")
    print()


def main():
    path = sys.argv[1]
    n = int(sys.argv[2]) if len(sys.argv) > 2 else 30

    cpu = load(path, "jdk.ExecutionSample")
    total, top, entry, inclusive = summarize(cpu, lambda e: 1)
    print(f"### CPU ({total:,} samples)\n")
    table("Top frame (self time, any code)", top, total, n, "samples")
    table("Charged to first Mojarra frame", entry, total, n, "samples")
    table("Mojarra inclusive", inclusive, total, n, "samples")

    alloc = load(path, "jdk.ObjectAllocationSample")
    total, top, entry, inclusive = summarize(alloc, lambda e: e["values"].get("weight", 0) or 0)
    print(f"### Allocation ({total / 1e6:,.0f} MB sampled weight)\n")
    table("Charged to first Mojarra frame", entry, total, n, "bytes")
    table("Mojarra inclusive", inclusive, total, n, "bytes")


if __name__ == "__main__":
    main()
