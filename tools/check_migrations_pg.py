"""
Apply every Flyway migration, in version order, to a throwaway PostgreSQL 16 server.

Uses the pip-installable `pgserver` package (bundles PostgreSQL + pgvector), so no Docker or
system PostgreSQL is needed. Each migration runs in its own transaction, like Flyway.

Before applying anything it also checks what Flyway would otherwise reject or silently skip:
  * a .sql file in the migration folder whose name is not V<version>__<description>.sql (Flyway ignores it, so the
    migration would never run; error),
  * two files with the same version (error),
  * a Flyway placeholder `${...}` in a migration (placeholder replacement is on by default and an undefined
    placeholder fails at start-up; error),
  * gaps in the version sequence (warning: allowed by Flyway, but a missing version added later is refused by
    `validate` on databases that already ran a higher one).

Usage:
    pip install pgserver psycopg2-binary
    python tools/check_migrations_pg.py [--dump-schema]
Exit code 1 on the first failing migration or any naming/placeholder error.
"""
from __future__ import annotations

import os
import re
import shutil
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


def preflight(names, read):
    """(errors, warnings) for the file names in the migration folder; read(name) returns a file's text."""
    errors, warnings = [], []
    for name in sorted(names):
        if re.match(r"^R__.+\.sql$", name):
            warnings.append(f"{name}: repeatable migration, not applied by this check")
        elif name.lower().endswith(".sql") and not version_key(name):
            errors.append(f"{name}: not a Flyway versioned migration name (V<version>__<description>.sql); "
                          f"Flyway would skip it")
    seen = {}
    for name in sorted((n for n in names if version_key(n)), key=version_key):
        key = version_key(name)
        if key in seen:
            errors.append(f"duplicate migration version {'.'.join(map(str, key))}: {seen[key]} and {name}")
        seen[key] = name
        if "${" in read(name):
            errors.append(f"{name}: contains '${{' (a Flyway placeholder); escape it or disable placeholder replacement")
    majors = sorted({key[0] for key in seen if len(key) == 1})
    missing = [v for v in range(1, majors[-1] + 1) if v not in majors] if majors else []
    if missing:
        warnings.append("version gap: no migration for " + ", ".join(f"V{v}" for v in missing))
    return errors, warnings


def main(argv):
    names = os.listdir(MIGRATIONS)
    errors, warnings = preflight(names, lambda n: open(os.path.join(MIGRATIONS, n), encoding="utf-8").read())
    for warning in warnings:
        print(f"WARN {warning}")
    for error in errors:
        print(f"ERROR {error}")
    if errors:
        return 1
    files = sorted((f for f in names if version_key(f)), key=version_key)
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
        try:
            server.cleanup()
        except Exception:  # noqa: BLE001 - best effort; the server stops at exit anyway
            pass
        shutil.rmtree(data_dir, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
