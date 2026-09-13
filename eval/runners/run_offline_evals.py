#!/usr/bin/env python3
"""Offline eval runner for trace and RAGAs-style regression checks."""

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any, Dict, List, Set, Tuple


def _load_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def _load_dataset(path: Path) -> Tuple[str, List[Dict[str, Any]]]:
    if not path.exists():
        raise FileNotFoundError(f"Dataset not found: {path}")

    if path.suffix == ".jsonl":
        scenarios: List[Dict[str, Any]] = []
        with path.open("r", encoding="utf-8") as handle:
            for idx, line in enumerate(handle, start=1):
                line = line.strip()
                if not line:
                    continue
                scenario = json.loads(line)
                if "id" not in scenario:
                    scenario["id"] = f"line-{idx}"
                scenarios.append(scenario)
        return path.stem, scenarios

    payload = _load_json(path)
    if isinstance(payload, list):
        return path.stem, payload
    if isinstance(payload, dict) and "scenarios" in payload and isinstance(payload["scenarios"], list):
        return str(payload.get("dataset", path.stem)), payload["scenarios"]
    raise ValueError("Unsupported dataset format. Use JSON object with 'scenarios', JSON array, or JSONL")


def _resolve_trace_path(dataset_path: Path, trace_ref: str) -> Path:
    trace_path = Path(trace_ref)
    if trace_path.is_absolute():
        return trace_path
    return (dataset_path.parent / trace_path).resolve()


def _latest_policy_decision(trace: Dict[str, Any]) -> Dict[str, Any]:
    decisions = trace.get("policy_decisions")
    if not isinstance(decisions, list) or not decisions:
        return {}
    latest = decisions[-1]
    return latest if isinstance(latest, dict) else {}


def _tool_names(trace: Dict[str, Any]) -> List[str]:
    calls = trace.get("tool_calls")
    if not isinstance(calls, list):
        return []
    names: List[str] = []
    for item in calls:
        if isinstance(item, dict):
            name = item.get("name")
            if isinstance(name, str):
                names.append(name)
    return names


def _required_trace_fields_present(trace: Dict[str, Any]) -> bool:
    required = ["runId", "steps", "tool_calls", "policy_decisions", "outcome"]
    return all(field in trace for field in required)


def _normalize(text: str) -> str:
    return re.sub(r"\s+", " ", text.lower().strip())


def _tokenize(text: str) -> Set[str]:
    tokens = re.findall(r"[a-zA-Zа-яА-Я0-9_]+", _normalize(text))
    return {token for token in tokens if len(token) >= 3}


def _faithfulness(statements: List[str], context: List[str]) -> float:
    if not statements:
        return 0.0
    normalized_context = "\n".join(_normalize(item) for item in context)
    supported = sum(1 for statement in statements if _normalize(statement) in normalized_context)
    return supported / len(statements)


def _relevance(goal: str, answer: str) -> float:
    goal_tokens = _tokenize(goal)
    if not goal_tokens:
        return 0.0
    answer_tokens = _tokenize(answer)
    overlap = goal_tokens.intersection(answer_tokens)
    return len(overlap) / len(goal_tokens)


def _evaluate_scenario(scenario: Dict[str, Any], trace: Dict[str, Any]) -> Dict[str, Any]:
    expected = scenario.get("expected", {})
    checks: List[Dict[str, Any]] = []

    def add_check(name: str, ok: bool, actual: Any, expected_value: Any) -> None:
        checks.append({
            "name": name,
            "pass": ok,
            "actual": actual,
            "expected": expected_value,
        })

    add_check("trace_has_required_fields", _required_trace_fields_present(trace), True, True)
    add_check(
        "trace_version",
        trace.get("traceVersion") == "productfactory.io/trace/v1",
        trace.get("traceVersion"),
        "productfactory.io/trace/v1",
    )

    outcome = trace.get("outcome", {}) if isinstance(trace.get("outcome"), dict) else {}
    decision = _latest_policy_decision(trace)
    tools = _tool_names(trace)

    if "outcome_status" in expected:
        actual = outcome.get("status")
        add_check("outcome_status", actual == expected["outcome_status"], actual, expected["outcome_status"])

    if "policy_allow" in expected:
        actual = decision.get("allow")
        add_check("policy_allow", actual == expected["policy_allow"], actual, expected["policy_allow"])

    if "require_human_approval" in expected:
        actual = decision.get("require_human_approval")
        add_check(
            "require_human_approval",
            actual == expected["require_human_approval"],
            actual,
            expected["require_human_approval"],
        )

    if "max_tool_calls" in expected:
        actual = len(tools)
        add_check("max_tool_calls", actual <= expected["max_tool_calls"], actual, f"<= {expected['max_tool_calls']}")

    if "must_include_tools" in expected:
        required_tools = expected["must_include_tools"]
        missing = [name for name in required_tools if name not in tools]
        add_check("must_include_tools", len(missing) == 0, {"tools": tools, "missing": missing}, required_tools)

    if "forbidden_tools" in expected:
        forbidden = expected["forbidden_tools"]
        found = [name for name in tools if name in forbidden]
        add_check("forbidden_tools", len(found) == 0, {"tools": tools, "found": found}, forbidden)

    if "min_score" in expected:
        score = outcome.get("score")
        ok = isinstance(score, (int, float)) and score >= expected["min_score"]
        add_check("min_score", ok, score, f">= {expected['min_score']}")

    passed_checks = sum(1 for check in checks if check["pass"])
    total_checks = len(checks)
    scenario_score = (passed_checks / total_checks) if total_checks else 0.0

    return {
        "scenario_id": scenario.get("id", "unknown"),
        "description": scenario.get("description", ""),
        "trace": scenario.get("trace"),
        "pass": passed_checks == total_checks,
        "score": round(scenario_score, 4),
        "checks": checks,
    }


def _evaluate_trace_dataset(
    mode: str,
    dataset_name: str,
    dataset_path: Path,
    scenarios: List[Dict[str, Any]],
    min_pass_rate: float,
    min_average_score: float,
) -> Tuple[Dict[str, Any], int]:
    results: List[Dict[str, Any]] = []

    for scenario in scenarios:
        trace_ref = scenario.get("trace")
        if not isinstance(trace_ref, str) or not trace_ref:
            results.append(
                {
                    "scenario_id": scenario.get("id", "unknown"),
                    "description": scenario.get("description", ""),
                    "trace": trace_ref,
                    "pass": False,
                    "score": 0.0,
                    "checks": [
                        {
                            "name": "trace_reference",
                            "pass": False,
                            "actual": trace_ref,
                            "expected": "non-empty string",
                        }
                    ],
                }
            )
            continue

        trace_path = _resolve_trace_path(dataset_path, trace_ref)
        if not trace_path.exists():
            results.append(
                {
                    "scenario_id": scenario.get("id", "unknown"),
                    "description": scenario.get("description", ""),
                    "trace": str(trace_path),
                    "pass": False,
                    "score": 0.0,
                    "checks": [
                        {
                            "name": "trace_exists",
                            "pass": False,
                            "actual": str(trace_path),
                            "expected": "existing file",
                        }
                    ],
                }
            )
            continue

        trace = _load_json(trace_path)
        if not isinstance(trace, dict):
            results.append(
                {
                    "scenario_id": scenario.get("id", "unknown"),
                    "description": scenario.get("description", ""),
                    "trace": str(trace_path),
                    "pass": False,
                    "score": 0.0,
                    "checks": [
                        {
                            "name": "trace_json_object",
                            "pass": False,
                            "actual": type(trace).__name__,
                            "expected": "object",
                        }
                    ],
                }
            )
            continue

        result = _evaluate_scenario(scenario, trace)
        result["trace"] = str(trace_path)
        results.append(result)

    total = len(results)
    passed = sum(1 for item in results if item["pass"])
    failed = total - passed
    pass_rate = (passed / total) if total else 0.0
    average_score = (sum(item["score"] for item in results) / total) if total else 0.0

    report = {
        "mode": mode,
        "dataset": dataset_name,
        "dataset_path": str(dataset_path),
        "metrics": {
            "total_scenarios": total,
            "passed_scenarios": passed,
            "failed_scenarios": failed,
            "pass_rate": round(pass_rate, 4),
            "average_score": round(average_score, 4),
        },
        "thresholds": {
            "min_pass_rate": min_pass_rate,
            "min_average_score": min_average_score,
        },
        "results": results,
    }

    exit_code = 0
    if failed > 0:
        exit_code = 1
    if pass_rate < min_pass_rate:
        exit_code = 1
    if average_score < min_average_score:
        exit_code = 1

    return report, exit_code


def _evaluate_ragas_scenario(scenario: Dict[str, Any]) -> Dict[str, Any]:
    expected = scenario.get("expected", {})
    checks: List[Dict[str, Any]] = []

    def add_check(name: str, ok: bool, actual: Any, expected_value: Any) -> None:
        checks.append(
            {
                "name": name,
                "pass": ok,
                "actual": actual,
                "expected": expected_value,
            }
        )

    goal = scenario.get("goal")
    answer = scenario.get("answer")
    context = scenario.get("context")
    statements = scenario.get("statements")

    valid_payload = (
        isinstance(goal, str)
        and isinstance(answer, str)
        and isinstance(context, list)
        and all(isinstance(item, str) for item in context)
        and isinstance(statements, list)
        and all(isinstance(item, str) for item in statements)
    )

    add_check("scenario_fields_valid", valid_payload, valid_payload, True)

    if not valid_payload:
        return {
            "scenario_id": scenario.get("id", "unknown"),
            "description": scenario.get("description", ""),
            "pass": False,
            "score": 0.0,
            "metrics": {},
            "checks": checks,
        }

    faithfulness = _faithfulness(statements, context)
    relevance = _relevance(goal, answer)

    min_faithfulness = expected.get("min_faithfulness", 0.7)
    min_relevance = expected.get("min_relevance", 0.6)

    add_check(
        "faithfulness",
        faithfulness >= min_faithfulness,
        round(faithfulness, 4),
        f">= {min_faithfulness}",
    )
    add_check(
        "relevance",
        relevance >= min_relevance,
        round(relevance, 4),
        f">= {min_relevance}",
    )

    passed_checks = sum(1 for check in checks if check["pass"])
    total_checks = len(checks)
    scenario_score = (passed_checks / total_checks) if total_checks else 0.0

    return {
        "scenario_id": scenario.get("id", "unknown"),
        "description": scenario.get("description", ""),
        "pass": passed_checks == total_checks,
        "score": round(scenario_score, 4),
        "metrics": {
            "faithfulness": round(faithfulness, 4),
            "relevance": round(relevance, 4),
        },
        "checks": checks,
    }


def _evaluate_ragas_dataset(
    dataset_name: str,
    dataset_path: Path,
    scenarios: List[Dict[str, Any]],
    min_pass_rate: float,
    min_average_score: float,
) -> Tuple[Dict[str, Any], int]:
    results = [_evaluate_ragas_scenario(scenario) for scenario in scenarios]

    total = len(results)
    passed = sum(1 for item in results if item["pass"])
    failed = total - passed
    pass_rate = (passed / total) if total else 0.0
    average_score = (sum(item["score"] for item in results) / total) if total else 0.0

    report = {
        "mode": "ragas",
        "dataset": dataset_name,
        "dataset_path": str(dataset_path),
        "metrics": {
            "total_scenarios": total,
            "passed_scenarios": passed,
            "failed_scenarios": failed,
            "pass_rate": round(pass_rate, 4),
            "average_score": round(average_score, 4),
        },
        "thresholds": {
            "min_pass_rate": min_pass_rate,
            "min_average_score": min_average_score,
        },
        "results": results,
    }

    exit_code = 0
    if failed > 0:
        exit_code = 1
    if pass_rate < min_pass_rate:
        exit_code = 1
    if average_score < min_average_score:
        exit_code = 1
    return report, exit_code


def run(mode: str, dataset_path: Path, min_pass_rate: float, min_average_score: float) -> Tuple[Dict[str, Any], int]:
    dataset_name, scenarios = _load_dataset(dataset_path)
    if mode in {"rag", "ragas"}:
        return _evaluate_ragas_dataset(dataset_name, dataset_path, scenarios, min_pass_rate, min_average_score)
    return _evaluate_trace_dataset(mode, dataset_name, dataset_path, scenarios, min_pass_rate, min_average_score)


def main() -> int:
    parser = argparse.ArgumentParser(description="Run offline evals")
    parser.add_argument("--mode", choices=["rag", "ragas", "agent", "security"], required=True, help="Eval mode")
    parser.add_argument("--dataset", required=True, help="Path to dataset (json/jsonl)")
    parser.add_argument("--report-out", help="Optional path to save JSON report")
    parser.add_argument("--min-pass-rate", type=float, default=1.0, help="Minimum acceptable pass rate")
    parser.add_argument("--min-average-score", type=float, default=0.95, help="Minimum acceptable average score")
    args = parser.parse_args()

    dataset_path = Path(args.dataset).resolve()
    report, exit_code = run(args.mode, dataset_path, args.min_pass_rate, args.min_average_score)

    payload = json.dumps(report, ensure_ascii=False, indent=2)
    print(payload)

    if args.report_out:
        output_path = Path(args.report_out)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(payload + "\n", encoding="utf-8")

    return exit_code


if __name__ == "__main__":
    sys.exit(main())
