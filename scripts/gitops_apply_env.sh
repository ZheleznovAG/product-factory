#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<USAGE
Usage:
  scripts/gitops_apply_env.sh <staging|prod|prod-canary> [--smoke]

Examples:
  scripts/gitops_apply_env.sh staging --smoke
  scripts/gitops_apply_env.sh prod
  scripts/gitops_apply_env.sh prod-canary --smoke
USAGE
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

ENVIRONMENT="${1:-}"
SMOKE="${2:-}"

if [[ -z "${ENVIRONMENT}" ]]; then
  echo "error: environment is required." >&2
  usage >&2
  exit 1
fi

if [[ -n "${SMOKE}" && "${SMOKE}" != "--smoke" ]]; then
  echo "error: only optional flag is --smoke." >&2
  usage >&2
  exit 1
fi

case "${ENVIRONMENT}" in
  staging)
    env_file="deploy/staging/gitops.env"
    compose_file="deploy/staging/docker-compose.yml"
    health_url="http://127.0.0.1:18080/health/ready"
    ;;
  prod)
    env_file="deploy/prod/gitops.env"
    compose_file="deploy/prod/docker-compose.yml"
    health_url="http://127.0.0.1:28080/health/ready"
    ;;
  prod-canary)
    env_file="deploy/prod-canary/gitops.env"
    compose_file="deploy/prod-canary/docker-compose.yml"
    health_url="http://127.0.0.1:28081/health/ready"
    ;;
  *)
    echo "error: unknown environment '${ENVIRONMENT}'." >&2
    usage >&2
    exit 1
    ;;
esac

if [[ ! -f "${env_file}" ]]; then
  echo "error: env file not found: ${env_file}" >&2
  exit 1
fi

if [[ ! -f "${compose_file}" ]]; then
  echo "error: compose file not found: ${compose_file}" >&2
  exit 1
fi

docker compose --env-file "${env_file}" -f "${compose_file}" up -d
echo "Applied desired state for ${ENVIRONMENT}."

if [[ "${SMOKE}" == "--smoke" ]]; then
  ./scripts/smoke-staging.sh "${health_url}"
  echo "Smoke passed for ${ENVIRONMENT}: ${health_url}"
fi
