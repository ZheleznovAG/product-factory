#!/usr/bin/env bash
# Останавливает контейнер фабрики и освобождает порты 9080 (factory) и 8090 (gateway).
# Запускайте перед ручным запуском сервисов или чтобы освободить порты.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/common.sh"

GATEWAY_PORT="${NEURAL_GATEWAY_PORT:-8090}"
FACTORY_PORT="${FACTORY_PORT:-9080}"
CONTAINER_NAME="${FACTORY_CONTAINER_NAME:-product-factory-run}"

echo "Остановка контейнера фабрики и освобождение портов $FACTORY_PORT, $GATEWAY_PORT..."
stop_container "$CONTAINER_NAME"
free_port "$FACTORY_PORT"
free_port "$GATEWAY_PORT"
echo "Готово."
