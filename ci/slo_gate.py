#!/usr/bin/env python3
"""SLO gate for CI based on JSON artifacts from live-run or eval."""

import argparse
import json
import sys
from pathlib import Path
from typing import Any, Dict, Optional


def read_json(path: Path) -> Dict[str, Any]:
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def pick_metric(payload: Dict[str, Any], key: str) -> Optional[float]:
    candidates = [payload, payload.get("metrics")]
    for item in candidates:
        if isinstance(item, dict) and key in item:
            value = item[key]
            try:
                return float(value)
            except (TypeError, ValueError):
                return None
    return None


def load_thresholds(path: Path) -> Dict[str, Any]:
    with path.open("r", encoding="utf-8") as f:
        raw = json.load(f)
    if not isinstance(raw, dict):
        raise ValueError("thresholds config root must be object")
    return raw


def config_value(
    config: Dict[str, Any],
    section: str,
    key: str,
    default: float,
) -> float:
    value = default
    section_obj = config.get(section, {})
    if isinstance(section_obj, dict) and key in section_obj:
        value = section_obj[key]
    try:
        return float(value)
    except (TypeError, ValueError) as exc:
        raise ValueError(
            f"invalid threshold value for {section}.{key}: {value}"
        ) from exc


def check_min(name: str, actual: Optional[float], threshold: float) -> Optional[str]:
    if actual is None:
        return f"missing metric: {name}"
    if actual < threshold:
        return f"{name}={actual} < min={threshold}"
    return None


def check_max(name: str, actual: Optional[float], threshold: float) -> Optional[str]:
    if actual is None:
        return f"missing metric: {name}"
    if actual > threshold:
        return f"{name}={actual} > max={threshold}"
    return None


def main() -> int:
    parser = argparse.ArgumentParser(description="SLO CI gate")
    parser.add_argument("--input", required=True, help="Path to JSON metrics artifact")
    parser.add_argument("--mode", choices=["live", "eval", "auto"], default="auto")
    parser.add_argument(
        "--thresholds-config",
        help="Optional JSON config file with live/eval thresholds",
    )

    parser.add_argument("--min-success-rate", type=float, default=95.0)
    parser.add_argument("--max-p50-seconds", type=float, default=900.0)
    parser.add_argument("--max-p99-seconds", type=float, default=7200.0)

    parser.add_argument("--min-pass-rate", type=float, default=1.0)
    parser.add_argument("--min-average-score", type=float, default=0.95)

    args = parser.parse_args()

    input_path = Path(args.input).resolve()
    if not input_path.exists():
        print(f"SLO gate failed: file not found: {input_path}")
        return 1

    try:
        payload = read_json(input_path)
    except json.JSONDecodeError as exc:
        print(f"SLO gate failed: invalid JSON: {exc}")
        return 1

    if not isinstance(payload, dict):
        print("SLO gate failed: JSON root must be an object")
        return 1

    thresholds: Dict[str, Any] = {}
    if args.thresholds_config:
        thresholds_path = Path(args.thresholds_config).resolve()
        if not thresholds_path.exists():
            print(f"SLO gate failed: thresholds config not found: {thresholds_path}")
            return 1
        try:
            thresholds = load_thresholds(thresholds_path)
        except (json.JSONDecodeError, ValueError) as exc:
            print(f"SLO gate failed: invalid thresholds config: {exc}")
            return 1

    live_min_success_rate = config_value(
        thresholds, "live", "min_success_rate", args.min_success_rate
    )
    live_max_p50_seconds = config_value(
        thresholds, "live", "max_p50_seconds", args.max_p50_seconds
    )
    live_max_p99_seconds = config_value(
        thresholds, "live", "max_p99_seconds", args.max_p99_seconds
    )
    eval_min_pass_rate = config_value(
        thresholds, "eval", "min_pass_rate", args.min_pass_rate
    )
    eval_min_average_score = config_value(
        thresholds, "eval", "min_average_score", args.min_average_score
    )

    failures = []
    checked = []

    if args.mode in ("live", "auto"):
        live_metrics = {
            "run_success_rate_percent": pick_metric(payload, "run_success_rate_percent"),
            "run_latency_p50_seconds": pick_metric(payload, "run_latency_p50_seconds"),
            "run_latency_p99_seconds": pick_metric(payload, "run_latency_p99_seconds"),
        }
        has_any_live = any(value is not None for value in live_metrics.values())

        if args.mode == "live" or has_any_live:
            checked.append("live")
            failures.extend(
                filter(
                    None,
                    [
                        check_min(
                            "run_success_rate_percent",
                            live_metrics["run_success_rate_percent"],
                            live_min_success_rate,
                        ),
                        check_max(
                            "run_latency_p50_seconds",
                            live_metrics["run_latency_p50_seconds"],
                            live_max_p50_seconds,
                        ),
                        check_max(
                            "run_latency_p99_seconds",
                            live_metrics["run_latency_p99_seconds"],
                            live_max_p99_seconds,
                        ),
                    ],
                )
            )

    if args.mode in ("eval", "auto"):
        eval_metrics = {
            "pass_rate": pick_metric(payload, "pass_rate"),
            "average_score": pick_metric(payload, "average_score"),
        }
        has_any_eval = any(value is not None for value in eval_metrics.values())

        if args.mode == "eval" or has_any_eval:
            checked.append("eval")
            failures.extend(
                filter(
                    None,
                    [
                        check_min("pass_rate", eval_metrics["pass_rate"], eval_min_pass_rate),
                        check_min(
                            "average_score",
                            eval_metrics["average_score"],
                            eval_min_average_score,
                        ),
                    ],
                )
            )

    if not checked:
        print("SLO gate failed: no supported metrics found in artifact")
        return 1

    print(
        "SLO gate summary: "
        f"mode={args.mode}, "
        f"input={input_path}, "
        f"checked={','.join(checked)}"
    )

    if failures:
        print("SLO gate failed:")
        for failure in failures:
            print(f"- {failure}")
        return 1

    print("SLO gate passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
