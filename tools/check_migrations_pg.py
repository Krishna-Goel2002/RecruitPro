"""
Apply every Flyway migration, in version order, to a throwaway PostgreSQL 16 server.

Uses the pip-installable `pgserver` package (bundles PostgreSQL + pgvector), so no Docker or
system PostgreSQL is needed. Each migration runs in its own transaction, like Flyway.

Usage:
    pip install pgserver psycopg2-binary
    python tools/check_migrations_pg.py [--dump-schema]
Exit code 1 on the first failing migration.
"""
from __future__ import annotations

import os
import re
import sys
import tempfile

import pgserver
import psycopg2

MIGRATIONS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "backend", "src", "main",
                          "resources", "db", "migration")
VERSION_RE = re.compile(r"^V(\d+(?:[._]\d+)*)__.+\.sql$")


def version_key(name: str):
    match = VERSION_RE.match(name)
    if not match:
        return None
    return tuple(int(part) for part in re.split(r"[._]", match.group(1)))


def main(argv):
    files = sorted((f for f in os.listdir(MIGRATIONS) if version_key(f)), key=version_key)
    seen = {}
    for f in files:
        key = version_key(f)
        if key in seen:
            print(f"Duplicate migration version {key}: {seen[key]} and {f}")
            return 1
        seen[key] = f
    data_dir = tempfile.mkdtemp(prefix="rp-pg-")
    server = pgserver.get_server(data_dir, cleanup_mode="stop")
    server.psql("CREATE DATABASE recruitpro_check;")
    uri = server.get_uri("recruitpro_check")
    conn = psycopg2.connect(uri)
    try:
        for f in files:
            with open(os.path.join(MIGRATIONS, f), encoding="utf-8") as fh:
                sql = fh.read()
            try:
                with conn:
                    with conn.cursor() as cur:
                        cur.execute(sql)
                print(f"OK   {f}")
            except Exception as exc:  # noqa: BLE001 - report any database error
                print(f"FAIL {f}\n     {str(exc).strip()}")
                return 1
        if "--dump-schema" in argv:
            with conn.cursor() as cur:
                cur.execute("""select table_name, column_name, data_type, is_nullable
                               from information_schema.columns where table_schema='public'
                               order by table_name, ordinal_position""")
                current = None
                for table, column, dtype, nullable in cur.fetchall():
                    if table != current:
                        print(f"\n{table}")
                        current = table
                    print(f"  {column:32} {dtype:28} {'NULL' if nullable == 'YES' else 'NOT NULL'}")
        print(f"\nApplied {len(files)} migrations to PostgreSQL successfully.")
        return 0
    finally:
        conn.close()


if __name__ == "__main__":
    sys.exit(main(sys.argv))
