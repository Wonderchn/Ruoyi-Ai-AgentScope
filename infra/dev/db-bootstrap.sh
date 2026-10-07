#!/bin/sh
# Idempotent database bootstrap for the dev delivery: superuser-only objects
# (roles, schemas, casts, vector extension) that are never migrations.
#
# 00-identity files are written to be re-runnable; CREATE CAST in the platform
# casts file is not, so it is skipped when the cast already exists. Any other
# failure aborts the stack start instead of continuing half-bootstrapped.
set -eu

psql_q() { psql -h postgres -U postgres -d ruoyi_agent -tAc "$1"; }
apply() {
    echo "applying $1"
    psql -h postgres -U postgres -d ruoyi_agent -v ON_ERROR_STOP=1 -f "$1" || exit 1
}

CASTS=$(psql_q "select count(*) from pg_cast c join pg_type s on s.oid=c.castsource join pg_type t on t.oid=c.casttarget where s.typname='varchar' and t.typname='timestamptz'" | tr -d '[:space:]')
if [ "$CASTS" = "1" ]; then
    echo "platform casts already applied; skipping"
else
    apply /sql/platform-bootstrap/01-platform-casts.sql
fi

apply /sql/platform-bootstrap/00-platform-identity.sql
apply /sql/ai-bootstrap/00-ai-identity.sql
echo "database bootstrap complete"
