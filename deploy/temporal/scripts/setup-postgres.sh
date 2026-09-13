#!/bin/sh
# Setup PostgreSQL schema for Temporal Server (from temporalio/samples-server).
# Host from POSTGRES_SEEDS (в compose: postgresql).
set -eu

PG_HOST="${POSTGRES_SEEDS:-postgresql}"
PG_PORT="${DB_PORT:-5432}"

echo 'Starting PostgreSQL schema setup...'
echo "Waiting for PostgreSQL at $PG_HOST:$PG_PORT..."
nc -z -w 10 "$PG_HOST" "$PG_PORT"
echo 'PostgreSQL port is available'

# Create and setup temporal database
temporal-sql-tool --plugin postgres12 --ep "$PG_HOST" -u temporal -p "$PG_PORT" --db temporal create
temporal-sql-tool --plugin postgres12 --ep "$PG_HOST" -u temporal -p "$PG_PORT" --db temporal setup-schema -v 0.0
temporal-sql-tool --plugin postgres12 --ep "$PG_HOST" -u temporal -p "$PG_PORT" --db temporal update-schema -d /etc/temporal/schema/postgresql/v12/temporal/versioned

# Create and setup visibility database
temporal-sql-tool --plugin postgres12 --ep "$PG_HOST" -u temporal -p "$PG_PORT" --db temporal_visibility create
temporal-sql-tool --plugin postgres12 --ep "$PG_HOST" -u temporal -p "$PG_PORT" --db temporal_visibility setup-schema -v 0.0
temporal-sql-tool --plugin postgres12 --ep "$PG_HOST" -u temporal -p "$PG_PORT" --db temporal_visibility update-schema -d /etc/temporal/schema/postgresql/v12/visibility/versioned

echo 'PostgreSQL schema setup complete'
