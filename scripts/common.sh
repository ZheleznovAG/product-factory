#!/usr/bin/env bash
# Общие функции для скриптов запуска: освобождение портов, остановка контейнеров.
# Подключать: source "$(dirname "${BASH_SOURCE[0]}")/common.sh" или source scripts/common.sh

# Освобождает порт: завершает процессы, слушающие на порту (macOS/Linux).
# Использование: free_port 8090
free_port() {
  local port="$1"
  [ -z "$port" ] && return 0
  local pids
  pids=$(lsof -ti:"$port" 2>/dev/null) || true
  if [ -n "$pids" ]; then
    echo "  Освобождаю порт $port (PIDs: $pids)"
    kill -9 $pids 2>/dev/null || true
    sleep 1
  fi
}

# Останавливает и удаляет контейнер по имени. Не падает, если контейнера нет.
# Использование: stop_container product-factory-run
stop_container() {
  local name="$1"
  [ -z "$name" ] && return 0
  if docker ps -q --filter "name=^${name}$" 2>/dev/null | grep -q .; then
    echo "  Останавливаю контейнер: $name"
    docker stop "$name" 2>/dev/null || true
  fi
  docker rm -f "$name" 2>/dev/null || true
}
