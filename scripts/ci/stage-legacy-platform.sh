#!/usr/bin/env bash
# Keep the supported pre-unification chain explicit. Later migrations belong to
# verify-unified-migrations.sh and must not leak into the two-schema checks.
stage_legacy_platform() {
  local source=$1 destination=$2 file
  mkdir -p "$destination" || return 2
  for file in \
    V1__platform_baseline.sql \
    V2__seed_system.sql \
    V3__restore_user_balance.sql \
    V4__ai_policy_revision.sql \
    V5__ai_run_permissions.sql \
    V6__agent_action_permissions.sql; do
    [ -f "$source/$file" ] || { echo "missing frozen migration: $file" >&2; return 2; }
    cp "$source/$file" "$destination/" || return 2
  done
}
