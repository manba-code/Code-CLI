#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
COMPOSE_FILE="$SCRIPT_DIR/paichange-m7a-compose.yml"

cleanup() {
  docker compose -f "$COMPOSE_FILE" down --volumes --remove-orphans
}
trap cleanup EXIT INT TERM

docker compose -f "$COMPOSE_FILE" up -d --wait postgres
cd "$PROJECT_DIR"
mvn test -Ppaichange-m7a-it
