#!/usr/bin/env python3
"""Run planner regression by comparing live planner outputs with reference products."""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple


EXPECTED_EVENT_TO_FILE = {
    "pipeline_plan": "pipeline_plan.json",
    "adr_draft": "adr_draft.json",
    "test_plan": "test_plan.json",
}
PLANNER_EXPLANATION_FILE = "planner_explanation.json"


@dataclass
class CaseResult:
    case: str
    run_id: Optional[str]
    passed: bool
    failures: List[str]


def _load_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def _load_text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def _post_json(url: str, payload: Dict[str, Any], timeout: float) -> Dict[str, Any]:
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        url=url,
        data=data,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read().decode("utf-8")
            return json.loads(body)
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {exc.code} for {url}: {body}") from exc
    except urllib.error.URLError as exc:
        raise RuntimeError(f"Cannot reach {url}: {exc}") from exc


def _scoped_run_id(tenant_id: Optional[str], run_id: str) -> str:
    if not tenant_id or tenant_id == "default":
        return run_id
    return f"{tenant_id}::{run_id}"


def _read_audit_events(audit_log: Path, scoped_run_id: str) -> List[Dict[str, Any]]:
    if not audit_log.exists():
        return []
    events: List[Dict[str, Any]] = []
    with audit_log.open("r", encoding="utf-8") as handle:
        for raw_line in handle:
            line = raw_line.strip()
            if not line:
                continue
            try:
                entry = json.loads(line)
            except json.JSONDecodeError:
                continue
            if entry.get("runId") != scoped_run_id:
                continue
            payload_raw = entry.get("payload")
            if not isinstance(payload_raw, str):
                continue
            try:
                payload = json.loads(payload_raw)
            except json.JSONDecodeError:
                payload = payload_raw
            events.append(
                {
                    "eventType": entry.get("eventType"),
                    "payload": payload,
                }
            )
    return events


def _wait_for_events(
    audit_log: Path,
    scoped_run_id: str,
    event_types: List[str],
    timeout_sec: float,
) -> Tuple[Optional[List[Dict[str, Any]]], List[str]]:
    deadline = time.time() + timeout_sec
    missing = list(event_types)
    while time.time() < deadline:
        events = _read_audit_events(audit_log, scoped_run_id)
        present = {event["eventType"] for event in events}
        missing = [event_type for event_type in event_types if event_type not in present]
        if not missing:
            return events, []
        time.sleep(0.5)
    return None, missing


def _build_request_payload(case_dir: Path) -> Dict[str, Any]:
    request_file = case_dir / "request.json"
    payload = _load_json(request_file)
    if not isinstance(payload, dict):
        raise ValueError(f"request.json must be an object: {request_file}")
    request = payload.get("request")
    if not isinstance(request, dict):
        raise ValueError(f"request.json missing 'request' object: {request_file}")

    contracts_ref = payload.get("contracts", {})
    contracts_payload: Dict[str, str] = {}
    if contracts_ref:
        if not isinstance(contracts_ref, dict):
            raise ValueError(f"'contracts' must be an object in {request_file}")
        for key, rel_path in contracts_ref.items():
            if not isinstance(rel_path, str) or not rel_path.strip():
                raise ValueError(f"Invalid contracts path for key '{key}' in {request_file}")
            contract_path = (case_dir / rel_path).resolve()
            contracts_payload[key] = _load_text(contract_path)

    output: Dict[str, Any] = dict(request)
    if contracts_payload:
        output["contracts"] = contracts_payload
    return output


def _expected_outputs(case_dir: Path) -> Dict[str, Any]:
    expected_dir = case_dir / "expected"
    output: Dict[str, Any] = {}
    for event_type, filename in EXPECTED_EVENT_TO_FILE.items():
        path = expected_dir / filename
        if path.exists():
            output[event_type] = _load_json(path)
    planner_explanation_path = expected_dir / PLANNER_EXPLANATION_FILE
    if planner_explanation_path.exists():
        output["planner_explanation"] = _load_json(planner_explanation_path)
    return output


def _extract_actual_from_events(events: List[Dict[str, Any]], expected: Dict[str, Any]) -> Dict[str, Any]:
    by_type: Dict[str, List[Any]] = {}
    for event in events:
        event_type = event.get("eventType")
        if not isinstance(event_type, str):
            continue
        by_type.setdefault(event_type, []).append(event.get("payload"))

    actual: Dict[str, Any] = {}
    for key in expected.keys():
        payloads = by_type.get(key, [])
        if not payloads:
            continue
        payload = payloads[-1]
        if key == "planner_explanation":
            if isinstance(payload, dict):
                actual[key] = {"explanation": payload.get("explanation")}
            else:
                actual[key] = {"explanation": None}
        else:
            actual[key] = payload
    return actual


def _diff_json(expected: Any, actual: Any, path: str = "$") -> List[str]:
    diffs: List[str] = []
    if type(expected) is not type(actual):
        diffs.append(f"{path}: type mismatch expected={type(expected).__name__} actual={type(actual).__name__}")
        return diffs

    if isinstance(expected, dict):
        exp_keys = set(expected.keys())
        act_keys = set(actual.keys())
        for missing in sorted(exp_keys - act_keys):
            diffs.append(f"{path}.{missing}: missing in actual")
        for extra in sorted(act_keys - exp_keys):
            diffs.append(f"{path}.{extra}: unexpected key in actual")
        for key in sorted(exp_keys & act_keys):
            diffs.extend(_diff_json(expected[key], actual[key], f"{path}.{key}"))
        return diffs

    if isinstance(expected, list):
        if len(expected) != len(actual):
            diffs.append(f"{path}: list length mismatch expected={len(expected)} actual={len(actual)}")
            return diffs
        for idx, (exp_item, act_item) in enumerate(zip(expected, actual)):
            diffs.extend(_diff_json(exp_item, act_item, f"{path}[{idx}]"))
        return diffs

    if expected != actual:
        diffs.append(f"{path}: expected={expected!r} actual={actual!r}")
    return diffs


def _run_case(case_dir: Path, factory_url: str, audit_log: Path, timeout_sec: float) -> CaseResult:
    case_name = case_dir.name
    failures: List[str] = []
    run_id: Optional[str] = None

    try:
        request_payload = _build_request_payload(case_dir)
        expected = _expected_outputs(case_dir)
        if not expected:
            return CaseResult(case=case_name, run_id=None, passed=False, failures=["No expected outputs found"])
    except Exception as exc:  # pylint: disable=broad-except
        return CaseResult(case=case_name, run_id=None, passed=False, failures=[f"Invalid case definition: {exc}"])

    response = _post_json(f"{factory_url.rstrip('/')}/factory/run", request_payload, timeout=timeout_sec)
    run_id = response.get("runId")
    if not isinstance(run_id, str) or not run_id:
        return CaseResult(case=case_name, run_id=None, passed=False, failures=[f"No runId in response: {response}"])

    status = response.get("status")
    if status != "accepted":
        failures.append(f"Run status is '{status}', expected 'accepted'")
        return CaseResult(case=case_name, run_id=run_id, passed=False, failures=failures)

    tenant_id = request_payload.get("tenantId")
    scoped_run_id = _scoped_run_id(tenant_id, run_id)
    must_have_events = sorted(expected.keys())
    events, missing = _wait_for_events(
        audit_log=audit_log,
        scoped_run_id=scoped_run_id,
        event_types=must_have_events,
        timeout_sec=timeout_sec,
    )
    if events is None:
        failures.append(f"Missing audit events for run '{scoped_run_id}': {', '.join(missing)}")
        return CaseResult(case=case_name, run_id=run_id, passed=False, failures=failures)

    actual = _extract_actual_from_events(events, expected)
    for key, expected_payload in expected.items():
        if key not in actual:
            failures.append(f"Missing actual payload for event '{key}'")
            continue
        diffs = _diff_json(expected_payload, actual[key], path=f"${key}")
        if diffs:
            failures.append(f"{key} mismatch:")
            failures.extend(diffs[:20])
            if len(diffs) > 20:
                failures.append(f"... and {len(diffs) - 20} more differences")

    return CaseResult(case=case_name, run_id=run_id, passed=not failures, failures=failures)


def _discover_cases(reference_root: Path, selected_cases: Optional[List[str]]) -> List[Path]:
    cases = [path for path in reference_root.iterdir() if path.is_dir() and (path / "request.json").exists()]
    cases.sort(key=lambda item: item.name)
    if not selected_cases:
        return cases
    selected = set(selected_cases)
    return [case for case in cases if case.name in selected]


def main() -> int:
    parser = argparse.ArgumentParser(description="Planner regression against eval/reference-products golden outputs")
    parser.add_argument("--reference-root", default="eval/reference-products", help="Path to reference products root")
    parser.add_argument("--factory-url", default="http://localhost:9080", help="Factory base URL")
    parser.add_argument("--audit-log", default="audit.log", help="Path to audit JSONL")
    parser.add_argument("--timeout-sec", type=float, default=30.0, help="Timeout per case for run + audit events")
    parser.add_argument("--cases", nargs="*", help="Optional case names to run")
    parser.add_argument("--report-out", help="Optional path to write JSON report")
    args = parser.parse_args()

    reference_root = Path(args.reference_root).resolve()
    audit_log = Path(args.audit_log).resolve()
    if not reference_root.exists():
        print(f"Reference root does not exist: {reference_root}", file=sys.stderr)
        return 1

    cases = _discover_cases(reference_root, args.cases)
    if not cases:
        print("No reference cases found", file=sys.stderr)
        return 1

    results: List[CaseResult] = []
    has_failures = False
    for case_dir in cases:
        try:
            case_result = _run_case(case_dir, args.factory_url, audit_log, args.timeout_sec)
        except Exception as exc:  # pylint: disable=broad-except
            case_result = CaseResult(case=case_dir.name, run_id=None, passed=False, failures=[str(exc)])
        results.append(case_result)
        if not case_result.passed:
            has_failures = True

    report = {
        "reference_root": str(reference_root),
        "factory_url": args.factory_url,
        "audit_log": str(audit_log),
        "total_cases": len(results),
        "passed_cases": sum(1 for result in results if result.passed),
        "failed_cases": sum(1 for result in results if not result.passed),
        "results": [
            {
                "case": result.case,
                "run_id": result.run_id,
                "passed": result.passed,
                "failures": result.failures,
            }
            for result in results
        ],
    }

    payload = json.dumps(report, ensure_ascii=False, indent=2)
    print(payload)
    if args.report_out:
        output_path = Path(args.report_out)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(payload + "\n", encoding="utf-8")

    return 1 if has_failures else 0


if __name__ == "__main__":
    sys.exit(main())

