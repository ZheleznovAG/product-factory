#!/usr/bin/env python3
"""Quality gates wrapper for offline eval runner."""

import argparse
import json
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Dict, Tuple


DEFAULT_THRESHOLDS: Dict[str, Tuple[float, float]] = {
    "rag": (1.0, 0.95),
    "ragas": (1.0, 1.0),
    "agent": (1.0, 0.95),
    "security": (1.0, 0.95),
}


def main() -> int:
    parser = argparse.ArgumentParser(description="Run quality gates")
    parser.add_argument("--mode", choices=["rag", "ragas", "agent", "security"], required=True)
    parser.add_argument("--dataset", required=True, help="Path to eval dataset")
    parser.add_argument("--min-pass-rate", type=float, help="Override min pass rate threshold")
    parser.add_argument("--min-average-score", type=float, help="Override min average score threshold")
    args = parser.parse_args()

    min_pass_rate, min_average_score = DEFAULT_THRESHOLDS[args.mode]
    if args.min_pass_rate is not None:
        min_pass_rate = args.min_pass_rate
    if args.min_average_score is not None:
        min_average_score = args.min_average_score

    dataset_path = Path(args.dataset).resolve()
    if not dataset_path.exists():
        print(f"Quality gate failed: dataset not found: {dataset_path}")
        return 1

    with tempfile.TemporaryDirectory(prefix="quality-gate-") as tmp_dir:
        report_path = Path(tmp_dir) / "eval_report.json"
        command = [
            sys.executable,
            "eval/runners/run_offline_evals.py",
            "--mode",
            args.mode,
            "--dataset",
            str(dataset_path),
            "--report-out",
            str(report_path),
            "--min-pass-rate",
            str(min_pass_rate),
            "--min-average-score",
            str(min_average_score),
        ]

        completed = subprocess.run(command, check=False)
        if not report_path.exists():
            print("Quality gate failed: eval report was not produced")
            return 1

        report = json.loads(report_path.read_text(encoding="utf-8"))

    metrics = report.get("metrics", {})
    print(
        "Quality gate summary: "
        f"mode={args.mode}, "
        f"dataset={report.get('dataset')}, "
        f"pass_rate={metrics.get('pass_rate')}, "
        f"average_score={metrics.get('average_score')}, "
        f"failed_scenarios={metrics.get('failed_scenarios')}"
    )

    if completed.returncode != 0:
        print("Quality gate failed: regression detected in eval run")
        return 1

    print("Quality gate passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
