#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
COMPOSE_FILE="$SCRIPT_DIR/paichange-m7b-compose.yml"
BACKUP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/paichange-m7b-backup.XXXXXX")

cleanup() {
  docker compose -f "$COMPOSE_FILE" down --volumes --remove-orphans
  rm -rf "$BACKUP_DIR"
}
trap cleanup EXIT INT TERM

docker compose -f "$COMPOSE_FILE" up -d --wait postgres minio minio-backup
docker compose -f "$COMPOSE_FILE" exec -T postgres createdb -U paichange paichange_identity
docker compose -f "$COMPOSE_FILE" run --rm mc \
  'mc alias set local http://minio:9000 paichange-test paichange-test-secret >/dev/null && mc alias set backup http://minio-backup:9000 paichange-backup paichange-backup-secret >/dev/null && mc mb --ignore-existing local/paichange-evidence && mc mb --ignore-existing backup/paichange-evidence-backup'

cd "$PROJECT_DIR"
mvn test -Ppaichange-m7b-it

RECOVERY_POINT_AT=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
docker compose -f "$COMPOSE_FILE" exec -T postgres pg_dump -U paichange -d paichange -Fc > "$BACKUP_DIR/paichange.dump"
docker compose -f "$COMPOSE_FILE" run --rm mc \
  'mc alias set local http://minio:9000 paichange-test paichange-test-secret >/dev/null && mc alias set backup http://minio-backup:9000 paichange-backup paichange-backup-secret >/dev/null && mc mirror --overwrite --remove local/paichange-evidence backup/paichange-evidence-backup'
BACKUP_COMPLETED_AT=$(date -u '+%Y-%m-%dT%H:%M:%SZ')

# Destroy both primary recovery sources so verification can only use the backup copies.
docker compose -f "$COMPOSE_FILE" exec -T postgres dropdb -U paichange paichange
docker compose -f "$COMPOSE_FILE" run --rm mc \
  'mc alias set local http://minio:9000 paichange-test paichange-test-secret >/dev/null && mc rb --force local/paichange-evidence'
RECOVERY_STARTED_AT=$(date -u '+%Y-%m-%dT%H:%M:%SZ')

docker compose -f "$COMPOSE_FILE" exec -T postgres createdb -U paichange paichange_restore
docker compose -f "$COMPOSE_FILE" exec -T postgres pg_restore -U paichange -d paichange_restore --no-owner --no-privileges < "$BACKUP_DIR/paichange.dump"
docker compose -f "$COMPOSE_FILE" run --rm mc \
  'mc alias set local http://minio:9000 paichange-test paichange-test-secret >/dev/null && mc alias set backup http://minio-backup:9000 paichange-backup paichange-backup-secret >/dev/null && mc mb --ignore-existing local/paichange-evidence-restored && mc mirror --overwrite backup/paichange-evidence-backup local/paichange-evidence-restored'

mvn test -Ppaichange-m7b-recovery-it \
  -Dpaichange.m7b.restore.jdbc=jdbc:postgresql://127.0.0.1:55434/paichange_restore \
  -Dpaichange.m7b.restore.bucket=paichange-evidence-restored \
  -Dpaichange.m7b.test.s3.endpoint=http://127.0.0.1:59002 \
  -Dpaichange.m7b.recovery.point-at="$RECOVERY_POINT_AT" \
  -Dpaichange.m7b.backup.completed-at="$BACKUP_COMPLETED_AT" \
  -Dpaichange.m7b.recovery.started-at="$RECOVERY_STARTED_AT"
