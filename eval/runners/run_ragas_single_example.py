#!/usr/bin/env python3
"""Minimal offline RAGAs-style eval for one example."""

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any, Dict, List, Set


def _load_json(path: Path) -> Dict[str, Any]:
    with path.open("r", encoding="utf-8") as handle:
        payload = json.load(handle)
    if not isinstance(payload, dict):
        raise ValueError("Input must be a JSON object")
    return payload


def _normalize(text: str) -> str:
    text = text.lower().strip()
    text = re.sub(r"\s+", " ", text)
    return text


def _tokenize(text: str) -> Set[str]:
    normalized = _normalize(text)
    tokens = re.findall(r"[a-zA-Zа-яА-Я0-9_]+", normalized)
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


def run(
    payload: Dict[str, Any],
    min_faithfulness: float,
    min_relevance: float,
) -> Dict[str, Any]:
    goal = payload.get("goal", "")
    answer = payload.get("answer", "")
    context = payload.get("context", [])
    statements = payload.get("statements", [])

    if not isinstance(goal, str) or not isinstance(answer, str):
        raise ValueError("Fields 'goal' and 'answer' must be strings")
    if not isinstance(context, list) or not all(isinstance(item, str) for item in context):
        raise ValueError("Field 'context' must be a list of strings")
    if not isinstance(statements, list) or not all(isinstance(item, str) for item in statements):
        raise ValueError("Field 'statements' must be a list of strings")

    faithfulness = _faithfulness(statements, context)
    relevance = _relevance(goal, answer)

    passed = faithfulness >= min_faithfulness and relevance >= min_relevance

    return {
        "id": payload.get("id", "single-example"),
        "metrics": {
            "faithfulness": round(faithfulness, 4),
            "relevance": round(relevance, 4),
        },
        "thresholds": {
            "min_faithfulness": min_faithfulness,
            "min_relevance": min_relevance,
        },
        "pass": passed,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Run minimal RAGAs-style eval for one example")
    parser.add_argument("--input", required=True, help="Path to JSON example")
    parser.add_argument("--min-faithfulness", type=float, default=0.7)
    parser.add_argument("--min-relevance", type=float, default=0.6)
    args = parser.parse_args()

    payload = _load_json(Path(args.input).resolve())
    report = run(payload, args.min_faithfulness, args.min_relevance)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["pass"] else 1


if __name__ == "__main__":
    sys.exit(main())
