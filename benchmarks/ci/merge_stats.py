"""Merges test/perf stats dumps of the same arm (summing counts and totals per scenario/phase) into one dump that
perfreport.py can read. Usage: python3 merge_stats.py out.txt in1.txt in2.txt ..."""
import sys

PHASES = {"RESTORE_VIEW", "APPLY_REQUEST_VALUES", "PROCESS_VALIDATIONS", "UPDATE_MODEL_VALUES", "INVOKE_APPLICATION", "RENDER_RESPONSE"}

out, inputs = sys.argv[1], sys.argv[2:]
rows, order, header = {}, [], None
for path in inputs:
    for line in open(path, encoding="utf-8"):
        parts = line.split()
        if line.startswith("# warmup=") and header is None:
            header = line.strip()
        if len(parts) < 7 or parts[1] not in PHASES:
            continue
        try:
            count, total, lo, hi = int(parts[2]), int(parts[3]), int(parts[5]), int(parts[6])
        except ValueError:
            continue
        key = (parts[0], parts[1])
        if key not in rows:
            rows[key] = [0, 0, lo, hi]
            order.append(key)
        r = rows[key]
        r[0] += count
        r[1] += total
        r[2] = min(r[2], lo)
        r[3] = max(r[3], hi)

with open(out, "w", encoding="utf-8") as f:
    f.write((header or "# merged") + f" merged={len(inputs)}\n# Faces perf stats (times in microseconds)\n\n")
    f.write(f"{'scenario':22} {'phase':28} {'count':>6} {'total_us':>12} {'avg_us':>10} {'min_us':>10} {'max_us':>10}\n")
    f.write("-" * 104 + "\n")
    for key in order:
        count, total, lo, hi = rows[key]
        f.write(f"{key[0]:22} {key[1]:28} {count:>6} {total:>12} {total // max(count, 1):>10} {lo:>10} {hi:>10}\n")
