"""
Lightweight static checks for the Spring Boot backend when no JDK/Maven is available.

What it catches (each is a guaranteed javac error):
  1. Syntax errors (tree-sitter-java parse errors / missing nodes).
  2. Imports of com.recruitpro.* types that do not exist.
  3. Simple type names (and annotation names) that are neither imported, declared in the
     same package, declared in the same file, type parameters, java.lang types, nor covered
     by a wildcard import.
  4. Duplicate top-level type names within one package.

What it cannot catch: method/field resolution, generics, overloads, Lombok-generated members,
annotation processing. Always run `mvnw verify` on a machine with JDK 21 before merging.

Usage:
    pip install tree-sitter tree-sitter-java
    python tools/java_static_check.py [backend/src]
Exit code 1 when any error is found.
"""
from __future__ import annotations

import os
import sys
from collections import defaultdict

import tree_sitter as ts
import tree_sitter_java as tsj

JAVA_LANG = set("""
Object String StringBuilder StringBuffer CharSequence Integer Long Short Byte Double Float Boolean Character
Number Math StrictMath System Thread ThreadLocal InheritableThreadLocal Runnable Iterable Comparable Cloneable
AutoCloseable Appendable Readable Class ClassLoader Enum Record Void Throwable Exception RuntimeException Error
IllegalArgumentException IllegalStateException NullPointerException UnsupportedOperationException
IndexOutOfBoundsException ArrayIndexOutOfBoundsException StringIndexOutOfBoundsException ArithmeticException
NumberFormatException ClassCastException SecurityException InterruptedException CloneNotSupportedException
ReflectiveOperationException ClassNotFoundException IllegalAccessException InstantiationException
NoSuchFieldException NoSuchMethodException ExceptionInInitializerError LinkageError StackOverflowError
OutOfMemoryError AssertionError NegativeArraySizeException ArrayStoreException IllegalMonitorStateException
IllegalThreadStateException IllegalCallerException EnumConstantNotPresentException TypeNotPresentException
Override Deprecated SuppressWarnings FunctionalInterface SafeVarargs Process ProcessBuilder Runtime Package
Module StackWalker VirtualMachineError InternalError UnknownError ThreadGroup Character
""".split())

# Names that tree-sitter may report as type identifiers but are not types.
NOT_TYPES = {"var"}


def node_text(node, src: bytes) -> str:
    return src[node.start_byte:node.end_byte].decode("utf-8", "replace")


def walk(node):
    stack = [node]
    while stack:
        n = stack.pop()
        yield n
        stack.extend(reversed(n.children))


class FileInfo:
    def __init__(self, path, src, tree):
        self.path = path
        self.src = src
        self.tree = tree
        self.package = ""
        self.imports: set[str] = set()          # fully qualified
        self.static_imports: set[str] = set()
        self.wildcards: set[str] = set()        # package or type prefixes
        self.declared: set[str] = set()         # every type declared anywhere in this file
        self.top_level: list[str] = []
        self.type_params: set[str] = set()


DECL_TYPES = {"class_declaration", "interface_declaration", "enum_declaration", "record_declaration",
              "annotation_type_declaration"}


def analyse(path: str, parser) -> FileInfo:
    with open(path, "rb") as fh:
        src = fh.read()
    tree = parser.parse(src)
    info = FileInfo(path, src, tree)
    root = tree.root_node
    for child in root.children:
        if child.type == "package_declaration":
            for c in child.children:
                if c.type in ("scoped_identifier", "identifier"):
                    info.package = node_text(c, src)
        elif child.type == "import_declaration":
            text = node_text(child, src).strip().rstrip(";")
            text = text[len("import"):].strip()
            is_static = text.startswith("static ")
            if is_static:
                text = text[len("static "):].strip()
            if text.endswith(".*"):
                info.wildcards.add(text[:-2])
            elif is_static:
                info.static_imports.add(text)
            else:
                info.imports.add(text)
        elif child.type in DECL_TYPES:
            name = child.child_by_field_name("name")
            if name is not None:
                info.top_level.append(node_text(name, src))
    for n in walk(root):
        if n.type in DECL_TYPES:
            name = n.child_by_field_name("name")
            if name is not None:
                info.declared.add(node_text(name, src))
        elif n.type == "type_parameter":
            for c in n.children:
                if c.type in ("identifier", "type_identifier"):
                    info.type_params.add(node_text(c, src))
                    break
    return info


def syntax_errors(info: FileInfo) -> list[str]:
    errors = []
    for n in walk(info.tree.root_node):
        if n.type == "ERROR" or n.is_missing:
            line = n.start_point[0] + 1
            snippet = node_text(n, info.src).splitlines()[0][:80] if n.end_byte > n.start_byte else n.type
            errors.append(f"{info.path}:{line}: syntax error near: {snippet!r}")
            if len(errors) >= 5:
                break
    return errors


def main(argv):
    base = argv[1] if len(argv) > 1 else os.path.join("backend", "src")
    parser = ts.Parser(ts.Language(tsj.language()))
    infos: list[FileInfo] = []
    for dirpath, _, files in os.walk(base):
        for name in files:
            if name.endswith(".java"):
                infos.append(analyse(os.path.join(dirpath, name), parser))

    # project index: fully qualified top-level types, plus nested types by "Outer.Inner"
    project_types: set[str] = set()
    package_types: dict[str, set[str]] = defaultdict(set)
    duplicates = []
    seen_top: dict[str, str] = {}
    for info in infos:
        for t in info.top_level:
            fq = f"{info.package}.{t}" if info.package else t
            if fq in seen_top and seen_top[fq] != info.path:
                duplicates.append(f"duplicate type {fq}: {seen_top[fq]} and {info.path}")
            seen_top[fq] = info.path
            project_types.add(fq)
            package_types[info.package].add(t)
        # nested types: register Outer.Inner for every declared non-top-level type
        for n in walk(info.tree.root_node):
            if n.type in DECL_TYPES:
                name = n.child_by_field_name("name")
                if name is None:
                    continue
                chain = [node_text(name, info.src)]
                p = n.parent
                while p is not None:
                    if p.type in DECL_TYPES:
                        pn = p.child_by_field_name("name")
                        if pn is not None:
                            chain.insert(0, node_text(pn, info.src))
                    p = p.parent
                if len(chain) > 1:
                    project_types.add(f"{info.package}." + ".".join(chain))

    errors: list[str] = list(duplicates)
    for info in infos:
        errors.extend(syntax_errors(info))
        for imp in info.imports:
            if imp.startswith("com.recruitpro.") and imp not in project_types:
                errors.append(f"{info.path}: import of unknown project type {imp}")
        for w in info.wildcards:
            if w.startswith("com.recruitpro.") and w not in package_types and w not in project_types:
                errors.append(f"{info.path}: wildcard import of unknown project package {w}")
        imported_simple = {i.rsplit(".", 1)[-1] for i in info.imports}
        static_simple = {i.rsplit(".", 1)[-1] for i in info.static_imports}
        same_package = package_types.get(info.package, set())
        has_foreign_wildcard = any(not w.startswith("com.recruitpro.") for w in info.wildcards)
        project_wildcard_types = set()
        for w in info.wildcards:
            if w in package_types:
                project_wildcard_types |= package_types[w]
            else:  # wildcard on a project type -> its nested types
                prefix = w + "."
                project_wildcard_types |= {t[len(prefix):].split(".")[0] for t in project_types if t.startswith(prefix)}
        known = (imported_simple | static_simple | same_package | info.declared | info.type_params
                 | JAVA_LANG | NOT_TYPES | project_wildcard_types)
        reported = set()
        for n in walk(info.tree.root_node):
            name = None
            if n.type == "type_identifier":
                # first segment of a scoped type (Map.Entry -> Map) is checked; inner segments skipped
                if n.parent is not None and n.parent.type == "scoped_type_identifier" and n.parent.children[0] != n:
                    continue
                name = node_text(n, info.src)
                # fully qualified names (java.util.X, org.foo.Y) start with a lower-case package segment
                if n.parent is not None and n.parent.type == "scoped_type_identifier" and name[:1].islower():
                    continue
            elif n.type in ("marker_annotation", "annotation"):
                nm = n.child_by_field_name("name")
                if nm is not None and nm.type == "identifier":
                    name = node_text(nm, info.src)
            if not name or name in known or name in reported:
                continue
            if has_foreign_wildcard:
                continue  # cannot prove absence
            reported.add(name)
            errors.append(f"{info.path}:{n.start_point[0] + 1}: unresolved type '{name}' (missing import?)")

    for e in errors:
        print(e)
    print(f"\nChecked {len(infos)} Java files: {len(errors)} error(s).")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
