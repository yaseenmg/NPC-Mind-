"""Source-level egress policy for project-owned Java code.

This is a review gate, not a sandbox: it catches direct networking APIs and common
reflective/class-loader indirection, but cannot prove that dependencies or native code
never access the network.
"""
from __future__ import annotations

import re

FORBIDDEN_PREFIXES = (
    "java.net.http.",
    "javax.net.",
    "javax.net",
    "okhttp3.",
    "org.apache.http.",
    "org.apache.hc.client5.",
    "io.netty.channel.socket.",
    "io.netty.handler.codec.http.",
    "io.ktor.client.",
)
IMPORT_RE = re.compile(
    r"\bimport\s+(?:static\s+)?([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*(?:\.\*)?|\*)\s*;"
)
JAVA_NET_FQ_RE = re.compile(r"\bjava\.net\.(?!URI\b|http\b)[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*")
JAVA_NET_HTTP_RE = re.compile(r"\bjava\.net\.http(?:\.[A-Za-z_$][\w$]*)*")
REFLECTION_PATTERNS = (
    (re.compile(r"\bClass\s*\.\s*forName\s*\("), "dynamic Class.forName loading"),
    (re.compile(r"\b[A-Za-z_$][\w$]*\s*\.\s*loadClass\s*\("), "dynamic loadClass invocation"),
    (re.compile(r"\bServiceLoader\s*\.\s*load\s*\("), "dynamic ServiceLoader provider loading"),
)


def strip_comments_and_literals(source: str) -> str:
    """Blank Java comments and literals while preserving line breaks and code positions."""
    out = list(source)
    i = 0
    n = len(source)
    while i < n:
        if source.startswith("//", i):
            j = i
            while j < n and source[j] != "\n":
                if source[j] != "\r": out[j] = " "
                j += 1
            i = j
            continue
        if source.startswith("/*", i):
            j = i
            end = source.find("*/", i + 2)
            stop = n if end < 0 else end + 2
            while j < stop:
                if source[j] not in "\r\n": out[j] = " "
                j += 1
            i = stop
            continue
        if source.startswith('"""', i):
            j = i
            end = source.find('"""', i + 3)
            stop = n if end < 0 else end + 3
            while j < stop:
                if source[j] not in "\r\n": out[j] = " "
                j += 1
            i = stop
            continue
        if source[i] in ('"', "'"):
            quote = source[i]
            j = i
            i += 1
            while i < n:
                ch = source[i]
                if ch not in "\r\n": out[i] = " "
                if ch == "\\":
                    i += 1
                    if i < n and source[i] not in "\r\n": out[i] = " "
                elif ch == quote:
                    i += 1
                    break
                i += 1
            continue
        i += 1
    return "".join(out)


def scan_java_source(path: str, source: str) -> list[str]:
    """Return policy violations with path and source line; harmless URI parsing is allowed."""
    code = strip_comments_and_literals(source)
    violations: list[str] = []
    seen: set[tuple[int, str]] = set()

    def add(match: re.Match[str], reason: str) -> None:
        line = code.count("\n", 0, match.start()) + 1
        key = (line, reason)
        if key not in seen:
            seen.add(key)
            violations.append(f"{path}:{line}: {reason}")

    for match in IMPORT_RE.finditer(code):
        imported = match.group(1)
        if imported.startswith("java.net.") and imported != "java.net.URI":
            add(match, f"network-capable import {imported}")
        elif imported == "java.net.*":
            add(match, "wildcard java.net import may expose networking APIs")
        elif any(imported.startswith(prefix) for prefix in FORBIDDEN_PREFIXES):
            add(match, f"network-capable import {imported}")

    for regex, reason in (
        (JAVA_NET_HTTP_RE, "java.net.http reference"),
        (JAVA_NET_FQ_RE, "java.net networking reference"),
        (re.compile(r"\bjavax\.net(?:\.[A-Za-z_$][\w$]*)*"), "javax.net reference"),
        (re.compile(r"\b(?:okhttp3|org\.apache\.http|org\.apache\.hc\.client5|io\.ktor\.client|io\.netty\.channel\.socket|io\.netty\.handler\.codec\.http)(?:\.[A-Za-z_$][\w$]*)*"), "third-party network client reference"),
        (re.compile(r"\.\s*toURL\s*\("), "URI-to-URL conversion can enable network access"),
    ):
        for match in regex.finditer(code):
            add(match, reason)

    for regex, reason in REFLECTION_PATTERNS:
        for match in regex.finditer(code):
            add(match, reason)
    if re.search(r"\bimport\s+(?:static\s+)?java\.lang\.reflect\.(?:\*|Method)\s*;", code):
        for match in re.finditer(r"\b[A-Za-z_$][\w$]*\s*\.\s*invoke\s*\(", code):
            add(match, "reflective method invocation requires security review")
    return violations
