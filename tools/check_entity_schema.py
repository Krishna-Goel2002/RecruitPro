"""
Check that every JPA entity mapping matches the PostgreSQL schema produced by the Flyway migrations.

This is what `spring.jpa.hibernate.ddl-auto: validate` enforces at start-up in dev/prod; it runs here without a
JDK. For every class annotated @Entity (and its @MappedSuperclass parents) it resolves the table name and the
column of every persistent field (@Column / @JoinColumn names, otherwise Spring Boot's
CamelCaseToUnderscoresNamingStrategy), applies all migrations to a throwaway PostgreSQL 16 (pgserver) and
reports:
  * tables or columns that do not exist,
  * Java types that Hibernate would not accept for the column's SQL type,
  * @Column(nullable = false) fields whose column is nullable (warning only).

Usage:
    pip install pgserver psycopg2-binary tree-sitter tree-sitter-java
    python tools/check_entity_schema.py [backend/src/main/java]
Exit code 1 when an error is found.
"""
from __future__ import annotations

import os
import re
import sys
import tempfile

import pgserver
import psycopg2
import tree_sitter as ts
import tree_sitter_java as tsj

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
MIGRATIONS = os.path.join(ROOT, "backend", "src", "main", "resources", "db", "migration")
VERSION_RE = re.compile(r"^V(\d+(?:[._]\d+)*)__.+\.sql$")
PARSER = ts.Parser(ts.Language(tsj.language()))

# Java type -> PostgreSQL information_schema.data_type values Hibernate accepts for it.
COMPATIBLE = {
    "String": {"character varying", "text", "character"},
    "Long": {"bigint"}, "long": {"bigint"},
    "Integer": {"integer", "smallint"}, "int": {"integer", "smallint"},
    "Boolean": {"boolean"}, "boolean": {"boolean"},
    "LocalDateTime": {"timestamp without time zone"},
    "LocalDate": {"date"},
    "Instant": {"timestamp with time zone", "timestamp without time zone"},
    "BigDecimal": {"numeric"},
    "Double": {"double precision"}, "double": {"double precision"},
}
SKIP_ANNOTATIONS = {"Transient", "OneToMany", "ManyToMany", "ElementCollection"}
RELATION_ANNOTATIONS = {"ManyToOne", "OneToOne"}


def text(node, src):
    return src[node.start_byte:node.end_byte].decode("utf-8")


def snake(name: str) -> str:
    return re.sub(r"(?<=[a-z0-9])([A-Z])", r"_\1", name).lower()


def annotations(modifiers_node, src):
    """{simpleName: argumentsText or ''} for the annotations in a modifiers node."""
    result = {}
    if modifiers_node is None:
        return result
    for child in modifiers_node.children:
        if child.type in ("annotation", "marker_annotation"):
            name_node = child.child_by_field_name("name")
            name = text(name_node, src).split(".")[-1]
            args = child.child_by_field_name("arguments")
            result[name] = text(args, src) if args is not None else ""
    return result


def named_arg(args: str, key: str):
    match = re.search(r"\b" + key + r"\s*=\s*\"([^\"]*)\"", args or "")
    return match.group(1) if match else None


def parse_sources(src_root):
    classes, enums = {}, set()
    for directory, _, files in os.walk(src_root):
        for filename in files:
            if not filename.endswith(".java"):
                continue
            path = os.path.join(directory, filename)
            with open(path, "rb") as fh:
                src = fh.read()
            tree = PARSER.parse(src)
            stack = [tree.root_node]
            while stack:
                node = stack.pop()
                stack.extend(node.children)
                if node.type == "enum_declaration":
                    enums.add(text(node.child_by_field_name("name"), src))
                if node.type != "class_declaration":
                    continue
                modifiers = next((c for c in node.children if c.type == "modifiers"), None)
                anns = annotations(modifiers, src)
                if "Entity" not in anns and "MappedSuperclass" not in anns:
                    continue
                name = text(node.child_by_field_name("name"), src)
                superclass = node.child_by_field_name("superclass")
                parent = None
                if superclass is not None:
                    parent = text(superclass, src).replace("extends", "").strip().split("<")[0].split(".")[-1]
                table = named_arg(anns.get("Table", ""), "name") or snake(name)
                fields = []
                body = node.child_by_field_name("body")
                for member in body.children:
                    if member.type != "field_declaration":
                        continue
                    fmods = next((c for c in member.children if c.type == "modifiers"), None)
                    mods_text = text(fmods, src) if fmods is not None else ""
                    if re.search(r"\b(static|transient)\b", mods_text):
                        continue
                    fanns = annotations(fmods, src)
                    if SKIP_ANNOTATIONS & set(fanns):
                        continue
                    ftype = text(member.child_by_field_name("type"), src)
                    for declarator in member.children:
                        if declarator.type != "variable_declarator":
                            continue
                        fname = text(declarator.child_by_field_name("name"), src)
                        fields.append((fname, ftype, fanns))
                classes[name] = {"path": path, "table": table, "parent": parent, "fields": fields,
                                 "entity": "Entity" in anns}
    return classes, enums


def expected_columns(cls_name, classes):
    cls = classes[cls_name]
    columns = []
    if cls["parent"] in classes:
        columns.extend(expected_columns(cls["parent"], classes))
    for fname, ftype, fanns in cls["fields"]:
        if RELATION_ANNOTATIONS & set(fanns):
            if "mappedBy" in fanns.get("OneToOne", ""):
                continue
            column = named_arg(fanns.get("JoinColumn", ""), "name") or snake(fname) + "_id"
            columns.append((fname, column, "__fk__", fanns))
        else:
            column = named_arg(fanns.get("Column", ""), "name") or snake(fname)
            columns.append((fname, column, ftype, fanns))
    return columns


def load_schema():
    files = sorted((f for f in os.listdir(MIGRATIONS) if VERSION_RE.match(f)),
                   key=lambda f: tuple(int(p) for p in re.split(r"[._]", VERSION_RE.match(f).group(1))))
    server = pgserver.get_server(tempfile.mkdtemp(prefix="rp-pg-schema-"), cleanup_mode="stop")
    server.psql("CREATE DATABASE recruitpro_schema;")
    conn = psycopg2.connect(server.get_uri("recruitpro_schema"))
    try:
        for f in files:
            with open(os.path.join(MIGRATIONS, f), encoding="utf-8") as fh:
                with conn:
                    with conn.cursor() as cur:
                        cur.execute(fh.read())
        with conn.cursor() as cur:
            cur.execute("""select table_name, column_name, data_type, is_nullable
                           from information_schema.columns where table_schema = 'public'""")
            schema = {}
            for table, column, dtype, nullable in cur.fetchall():
                schema.setdefault(table, {})[column] = (dtype, nullable == "YES")
        return schema, len(files)
    finally:
        conn.close()


def main(argv):
    src_root = argv[1] if len(argv) > 1 else os.path.join(ROOT, "backend", "src", "main", "java")
    classes, enums = parse_sources(src_root)
    schema, migrations = load_schema()
    errors, warnings, checked = [], [], 0
    for name, cls in sorted(classes.items()):
        if not cls["entity"]:
            continue
        table = cls["table"]
        if table not in schema:
            errors.append(f"{name}: table '{table}' does not exist")
            continue
        for fname, column, ftype, fanns in expected_columns(name, classes):
            checked += 1
            if column not in schema[table]:
                errors.append(f"{name}.{fname}: column '{table}.{column}' does not exist")
                continue
            dtype, nullable = schema[table][column]
            base = ftype.split("<")[0].split(".")[-1]
            if ftype == "__fk__":
                allowed = {"bigint"}
            elif "Convert" in fanns:
                allowed = {"text", "character varying"}
            elif base in enums:
                allowed = {"character varying"} if "STRING" in fanns.get("Enumerated", "") else {"integer", "smallint"}
            else:
                allowed = COMPATIBLE.get(base)
            if allowed is not None and dtype not in allowed:
                errors.append(f"{name}.{fname} ({ftype}): column '{table}.{column}' is {dtype}")
            if "nullable = false" in fanns.get("Column", "").replace("nullable=false", "nullable = false") and nullable:
                warnings.append(f"{name}.{fname}: mapped nullable = false but '{table}.{column}' allows NULL")
    for warning in warnings:
        print(f"WARN  {warning}")
    for error in errors:
        print(f"ERROR {error}")
    entities = sum(1 for c in classes.values() if c["entity"])
    print(f"\nChecked {checked} mapped columns of {entities} entities against {migrations} migrations: "
          f"{len(errors)} error(s), {len(warnings)} warning(s).")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
