#!/usr/bin/env python3
"""Static gate: ensure release workflow enforces supply-chain policy controls."""

import argparse
import re
import sys
from pathlib import Path
from typing import List, Tuple


def check_required_patterns(content: str) -> List[str]:
    required: List[Tuple[str, str]] = [
        (
            "trigger on version tags",
            r"push:\s*\n\s*tags:\s*\n\s*-\s*[\"']?v\*",
        ),
        (
            "manual approval job exists",
            r"jobs:\s*\n\s*high-risk-manual-approval:",
        ),
        (
            "manual approval uses protected environment",
            r"high-risk-manual-approval:[\s\S]*?environment:\s*\n\s*name:\s*high-risk-release",
        ),
        (
            "release job depends on manual approval",
            r"release-images:\s*\n\s*needs:\s*high-risk-manual-approval",
        ),
        (
            "SBOM generation step",
            r"uses:\s*anchore/sbom-action@",
        ),
        (
            "image signing step",
            r"cosign\s+sign\s+--yes",
        ),
        (
            "signature verification step",
            r"cosign\s+verify",
        ),
        (
            "provenance attestation step",
            r"uses:\s*actions/attest-build-provenance@",
        ),
    ]

    missing: List[str] = []
    for name, pattern in required:
        if re.search(pattern, content, flags=re.MULTILINE) is None:
            missing.append(name)

    return missing


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate release policy controls in workflow")
    parser.add_argument(
        "--workflow",
        default=".github/workflows/release.yml",
        help="Path to release workflow file",
    )
    args = parser.parse_args()

    workflow_path = Path(args.workflow).resolve()
    if not workflow_path.exists():
        print(f"Release policy gate failed: workflow not found: {workflow_path}")
        return 1

    content = workflow_path.read_text(encoding="utf-8")
    missing = check_required_patterns(content)

    print(f"Release policy gate summary: workflow={workflow_path}")
    if missing:
        print("Release policy gate failed: missing required controls")
        for item in missing:
            print(f"- {item}")
        return 1

    print("Release policy gate passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
