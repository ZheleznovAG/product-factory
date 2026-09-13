#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<USAGE
Usage:
  scripts/gitops_promote_staging_to_prod.sh [IMAGE_REF]

Arguments:
  IMAGE_REF                        Optional explicit immutable image reference.
                                   Example: ghcr.io/acme/catalog-service@sha256:abc...

Environment variables:
  STAGING_ENV_FILE                Source env file (default: deploy/staging/gitops.env)
  PROD_ENV_FILE                   Target prod env file (default: deploy/prod/gitops.env)
  PROD_CANARY_ENV_FILE            Optional canary env file (default: deploy/prod-canary/gitops.env)
  TARGET                          Update target: prod|canary|all (default: prod)
  UPDATE_CANARY                   Legacy mode:
                                  - with TARGET unset and UPDATE_CANARY=1 => TARGET=all
                                  - ignored when TARGET is explicitly set

Behavior:
  - If IMAGE_REF is omitted, reads CATALOG_SERVICE_IMAGE from STAGING_ENV_FILE.
  - Requires immutable digest reference (image@sha256:...).
  - Updates or creates CATALOG_SERVICE_IMAGE in the selected target env file(s).
USAGE
}

STAGING_ENV_FILE="${STAGING_ENV_FILE:-deploy/staging/gitops.env}"
PROD_ENV_FILE="${PROD_ENV_FILE:-deploy/prod/gitops.env}"
PROD_CANARY_ENV_FILE="${PROD_CANARY_ENV_FILE:-deploy/prod-canary/gitops.env}"
TARGET="${TARGET:-}"
UPDATE_CANARY="${UPDATE_CANARY:-0}"

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

IMAGE_REF="${1:-}"

if [[ -z "${IMAGE_REF}" ]]; then
  if [[ ! -f "${STAGING_ENV_FILE}" ]]; then
    echo "error: ${STAGING_ENV_FILE} not found. Pass IMAGE_REF or create source env file." >&2
    exit 1
  fi
  IMAGE_REF="$(grep -E '^CATALOG_SERVICE_IMAGE=' "${STAGING_ENV_FILE}" | tail -n1 | cut -d= -f2-)"
fi

if [[ -z "${IMAGE_REF}" ]]; then
  echo "error: CATALOG_SERVICE_IMAGE is empty." >&2
  exit 1
fi

if [[ ! "${IMAGE_REF}" =~ @sha256:[a-f0-9]{64}$ ]]; then
  echo "error: IMAGE_REF must be immutable digest reference image@sha256:<64-hex>." >&2
  echo "got: ${IMAGE_REF}" >&2
  exit 1
fi

write_env_image() {
  local file="$1"
  mkdir -p "$(dirname "${file}")"
  touch "${file}"

  if grep -q '^CATALOG_SERVICE_IMAGE=' "${file}"; then
    sed -i.bak "s|^CATALOG_SERVICE_IMAGE=.*|CATALOG_SERVICE_IMAGE=${IMAGE_REF}|" "${file}"
  else
    printf 'CATALOG_SERVICE_IMAGE=%s\n' "${IMAGE_REF}" >> "${file}"
  fi
  rm -f "${file}.bak"
}

if [[ -z "${TARGET}" ]]; then
  if [[ "${UPDATE_CANARY}" == "1" ]]; then
    TARGET="all"
  else
    TARGET="prod"
  fi
fi

case "${TARGET}" in
  prod|canary|all) ;;
  *)
    echo "error: TARGET must be one of: prod, canary, all." >&2
    echo "got: ${TARGET}" >&2
    exit 1
    ;;
esac

targets=()
if [[ "${TARGET}" == "prod" || "${TARGET}" == "all" ]]; then
  targets+=("${PROD_ENV_FILE}")
fi
if [[ "${TARGET}" == "canary" || "${TARGET}" == "all" ]]; then
  targets+=("${PROD_CANARY_ENV_FILE}")
fi

for target_file in "${targets[@]}"; do
  write_env_image "${target_file}"
  echo "Updated ${target_file}: CATALOG_SERVICE_IMAGE=${IMAGE_REF}"
done

add_files=""
for target_file in "${targets[@]}"; do
  add_files="${add_files} ${target_file}"
done

cat <<NEXT
Next:
  1) git add${add_files}
  2) git commit -m "promote: catalog-service ${IMAGE_REF} to prod"
  3) git push and open PR
NEXT
