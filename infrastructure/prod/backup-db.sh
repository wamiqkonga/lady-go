#!/usr/bin/env bash
# Nightly Postgres backup: pg_dump -> gzip -> verify -> private S3 bucket (30-day expiry rule on the bucket).
# Runs on the production host from /etc/cron.d/ladygo-backup. S3/SNS access comes from the instance role.
# On any failure it publishes to the ladygo-alerts SNS topic (email) so a broken backup is never silent.
set -euo pipefail

REGION="ap-south-1"
ACCOUNT="332053469094"
BUCKET="ladygo-backups-${ACCOUNT}-aps1"
TOPIC="arn:aws:sns:${REGION}:${ACCOUNT}:ladygo-alerts"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
KEY="db/ladygo-${STAMP}.sql.gz"
TMP="$(mktemp /opt/ladygo/.dump.XXXXXX)"
# Pinned by digest (aws-cli 2.38.3): the container runs with the instance role's credentials, so a moving
# "latest" tag from a public registry is a supply-chain risk. To upgrade: pull the new tag, test a backup,
# then replace this digest.
AWS_CLI_IMAGE="amazon/aws-cli@sha256:efe32e825cf203652eaca3e7e94679580274e7d567c39270aaa36a5144737108"

alert() {
  docker run --rm "$AWS_CLI_IMAGE" sns publish --region "$REGION" --topic-arn "$TOPIC" \
    --subject "Lady Go: database backup FAILED" --message "$1" >/dev/null 2>&1 || true
}
cleanup() { rm -f "$TMP"; }
fail() { echo "$(date -u +%FT%TZ) BACKUP FAILED: $1" >&2; alert "Backup $STAMP failed on $(hostname): $1"; exit 1; }
trap cleanup EXIT

docker exec ladygo-postgres-1 pg_dump -U ladygo -d ladygo --no-owner --clean --if-exists | gzip -9 > "$TMP" \
  || fail "pg_dump failed"
gzip -t "$TMP" || fail "dump is not a valid gzip file"
SIZE="$(stat -c %s "$TMP")"
[ "$SIZE" -gt 2000 ] || fail "dump is suspiciously small ($SIZE bytes)"

docker run --rm -i "$AWS_CLI_IMAGE" s3 cp - "s3://${BUCKET}/${KEY}" --region "$REGION" < "$TMP" >/dev/null \
  || fail "upload to S3 failed"

echo "$(date -u +%FT%TZ) backup ok ${KEY} ${SIZE} bytes"
