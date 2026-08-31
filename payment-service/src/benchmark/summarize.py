"""Aggregate verified benchmark CSVs; repeat=0 is calibration/warmup, never a result.

Usage: python summarize.py <run-directory> [<run-directory> ...] --output <report.md>
Do not mix Windows calibration runs with Docker measurement runs.
"""
import argparse
import csv
import json
import statistics
import sys
from collections import defaultdict
from pathlib import Path


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("runs", type=Path, nargs="+")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    grouped = defaultdict(list)
    environments = []
    for run in args.runs:
        environments.append(json.loads((run / "environment.json").read_text(encoding="utf-8")))
        with (run / "results.csv").open(encoding="utf-8", newline="") as stream:
            for row in csv.DictReader(stream):
                if int(row["repeat"]) > 0 and row["scenario"] != "warmup":
                    grouped[(int(row["payments"]), row["scenario"], row["mode"])].append(row)
    for key in ("java", "os", "processors", "maxHeap", "baseline", "chunkSize", "payoutModel", "payoutIsolation"):
        if len({str(env.get(key, 10 if key == "chunkSize" else None)) for env in environments}) > 1:
            raise ValueError(f"Different environments: {key}; do not pool these runs")
    if not grouped:
        raise ValueError("No verified measurement rows")
    lines = ["# 정산 성능 실측 결과", "", "지급 정합성을 통과한 CSV 행만 집계했다. 워밍업은 제외했다.", "",
             "| 결제 건수 | 분포 | 방식 | 반복 | 중앙값(초) | 최소–최대(초) | 결제/초 | 단일 대비 배율 |",
             "|---:|---|---|---:|---:|---:|---:|---:|"]
    summaries = []
    for (size, scenario, mode), rows in sorted(grouped.items()):
        wall = [int(row["wall_ms"]) / 1000 for row in rows]
        median = statistics.median(wall)
        single = grouped.get((size, scenario, "single"))
        ratio = statistics.median([int(row["wall_ms"]) / 1000 for row in single]) / median if single else None
        ratio_text = f"{ratio:.2f}x" if ratio else "—"
        lines.append(f"| {size:,} | {scenario} | {mode} | {len(rows)} | {median:.3f} | "
                     f"{min(wall):.3f}–{max(wall):.3f} | {size / median:.1f} | {ratio_text} |")
        summaries.append(dict(payments=size, scenario=scenario, mode=mode, repetitions=len(rows),
                              median_seconds=median, min_seconds=min(wall), max_seconds=max(wall),
                              payments_per_second=size / median, speedup_vs_single=ratio))
    lines += ["", "## 단계·쓰기·락 대기 지표", "",
              "각 수치는 반복 중앙값이다. 직접 지급의 준비/입금 칸은 해당 없음(0)이다.", "",
              "| 결제 | 분포 | 방식 | 준비(초) | 입금(초) | 지갑 UPDATE | 락 대기 횟수 | 락 대기(ms) | GC(ms) | Outbox 행 |",
              "|---:|---|---|---:|---:|---:|---:|---:|---:|---:|"]
    for (size, scenario, mode), rows in sorted(grouped.items()):
        def median(field):
            return statistics.median(int(row[field]) for row in rows)
        lines.append(f"| {size:,} | {scenario} | {mode} | {median('prepare_ms') / 1000:.3f} | "
                     f"{median('payout_ms') / 1000:.3f} | {median('wallet_updates'):g} | "
                     f"{median('lock_waits'):g} | {median('lock_wait_ms'):g} | {median('gc_ms'):g} | "
                     f"{median('outbox_rows'):g} |")
    sizes = sorted({key[0] for key in grouped})
    incomplete = [(size, scenario, mode) for size in sizes for scenario in ("uniform", "hot90")
                  for mode in ("single", "partitioned", "two-stage")
                  if len(grouped.get((size, scenario, mode), [])) != 3]
    lines += ["", "## 한계", "",
              "- 결제 1건 = 정산 원천 2행. 단일/파티셔닝 기준은 36e3b8e9의 직접 지급 복제본이다.",
              "- DB Job과 Outbox 저장까지 포함한다. 원천 생성, Kafka 발행, 운영 동시 부하는 제외한다.",
              "- 캐시를 강제로 비우지 않았다. 실행 순서와 JVM 워밍업의 잔여 효과가 있을 수 있다.",
              "- 전체 시간만으로 락 병목 개선을 단정하지 않는다. 파티션 시간과 쓰기/대기 지표를 함께 본다."]
    if incomplete:
        lines.append(f"- **미완료/반복 부족 조합:** {incomplete}. 3회 비교 완료로 해석하면 안 된다.")
    lines += ["", "## 원본", ""] + [f"- {run.resolve()}" for run in args.runs]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("\n".join(lines) + "\n", encoding="utf-8")
    args.output.with_suffix(".json").write_text(json.dumps(summaries, indent=2), encoding="utf-8")
    print("\n".join(lines[:8 + len(grouped)]))


if __name__ == "__main__":
    main()
