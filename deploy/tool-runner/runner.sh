#!/bin/sh
# Tool runner: выполняет create_repo_from_archetype и apply_patch в изолированном контейнере.
# Вход: env TOOL_NAME, для create_repo — ARCHETYPE_ID, REPO_NAME; для apply_patch — REPO_NAME, PATCH_B64 (base64).
# Выход: одна строка JSON в stdout: {"success":true|false,"message":"...","result":{...}}

set -e
export PATH="/usr/bin:/bin"

emit() {
  echo "$1"
}

fail() {
  emit "{\"success\":false,\"message\":\"$1\",\"result\":{}}"
  exit 0
}

succeed() {
  # $1 = message, $2 = result JSON object (without braces if empty)
  if [ -n "$2" ]; then
    emit "{\"success\":true,\"message\":\"$1\",\"result\":$2}"
  else
    emit "{\"success\":true,\"message\":\"$1\",\"result\":{}}"
  fi
  exit 0
}

escape_json() {
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g; s/\t/\\t/g; s/\r/\\r/g' | tr -d '\n'
}

case "${TOOL_NAME}" in
  create_repo_from_archetype)
    [ -z "${ARCHETYPE_ID}" ] && fail "ARCHETYPE_ID is required"
    [ -z "${REPO_NAME}" ] && fail "REPO_NAME is required"
    SRC="/archetypes/${ARCHETYPE_ID}"
    DST="/workspace/${REPO_NAME}"
    [ ! -d "$SRC" ] && fail "archetype not found: ${ARCHETYPE_ID}"
    [ -d "$DST" ] && [ -n "$(ls -A "$DST" 2>/dev/null)" ] && fail "target directory not empty: ${REPO_NAME}"
    mkdir -p /workspace
    cp -R "$SRC" "$DST"
    result="{\"repo_path\":\"$DST\",\"repo_name\":\"$REPO_NAME\",\"archetype_id\":\"$ARCHETYPE_ID\"}"
    succeed "Repository scaffolded from archetype in sandbox workspace" "$result"
    ;;
  apply_patch)
    [ -z "${REPO_NAME}" ] && fail "REPO_NAME is required"
    DIR="/workspace/${REPO_NAME}"
    [ ! -d "$DIR" ] && fail "repo directory not found: ${REPO_NAME}"
    if [ -f /input/patch ]; then
      PATCH_FILE="/input/patch"
    elif [ -n "${PATCH_B64}" ]; then
      PATCH_FILE="/tmp/pf.patch"
      echo "$PATCH_B64" | base64 -d > "$PATCH_FILE" 2>/dev/null || fail "invalid PATCH_B64"
    else
      fail "PATCH_B64 or /input/patch is required"
    fi
    if ! (cd "$DIR" && git apply --ignore-whitespace "$PATCH_FILE" 2>&1); then
      code=$?
      fail "git apply failed with exit code $code"
    fi
    result="{\"repo_name\":\"$REPO_NAME\",\"applied\":true}"
    succeed "Patch applied" "$result"
    ;;
  *)
    fail "unknown tool: ${TOOL_NAME}"
    ;;
esac
