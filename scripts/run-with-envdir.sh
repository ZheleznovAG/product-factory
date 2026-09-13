#!/usr/bin/env bash
# Загружает переменные из env.d (формат: файл = переменная) и выполняет команду.
# Для локальной разработки удобнее direnv + .envrc (см. env.d/README.md).
# Использование: run-with-envdir.sh [DIR] -- COMMAND [args...]
set -e
ENVDIR="env.d"
if [[ "$1" == "--" ]]; then
  shift
elif [[ -n "$1" && "$1" != "--" ]] && [[ -d "$1" ]]; then
  ENVDIR="$1"
  shift
  [[ "$1" == "--" ]] && shift
fi
for f in "$ENVDIR"/*; do
  if [[ -f "$f" ]]; then
    name=$(basename "$f")
    [[ "$name" =~ ^[a-zA-Z_][a-zA-Z0-9_]*$ ]] && export "$name=$(cat "$f" | tr -d '\n')"
  fi
done
exec "$@"
