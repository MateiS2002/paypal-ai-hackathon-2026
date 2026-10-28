#!/bin/sh
# Creates one database per service, listed in POSTGRES_DATABASES (space separated).
# Postgres runs this only when its data volume is empty. After adding a name, reset with: docker compose down -v
set -eu
for db in $POSTGRES_DATABASES; do
  echo "Creating database $db"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres -c "CREATE DATABASE \"$db\";"
done
