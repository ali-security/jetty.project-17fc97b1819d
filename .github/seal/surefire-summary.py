#!/usr/bin/env python3
"""Print per-module surefire totals and a reason for every skipped test.

Surefire 2.17 prints only "Skipped: N". Upstream jetty skips tests with bare
@Ignore / @Stress / @Slow annotations or JUnit Assume calls, which leave the
<skipped> element without a message. For those, the annotation and comment
lines above the test method (and Assume lines in its body) are recovered from
the test source and printed as the skip reason.
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET


def find_source(module_dir, classname):
    rel = classname.split("$")[0].replace(".", "/") + ".java"
    for root in ("src/test/java", "src/main/java"):
        path = os.path.join(module_dir, root, rel)
        if os.path.isfile(path):
            return path
    return None


def class_level_reason(lines):
    out = []
    for i, line in enumerate(lines):
        if re.match(r"\s*public\s+(abstract\s+)?class\s", line):
            j = i - 1
            while j >= 0 and (lines[j].strip().startswith("@") or lines[j].strip().startswith("//")):
                out.insert(0, lines[j].strip())
                j -= 1
            break
    return [x for x in out if re.search(r"@(Ignore|Stress|Slow)|//", x)]


def method_body(lines, start):
    """Return the lines of the method whose signature is at lines[start]."""
    out = []
    depth = 0
    started = False
    for k in range(start, min(start + 200, len(lines))):
        out.append(lines[k])
        depth += lines[k].count("{") - lines[k].count("}")
        if "{" in lines[k]:
            started = True
        if started and depth <= 0:
            break
    return out


def helper_reason(lines, start, method):
    """The test delegates to a helper in the same file: print the helper's
    comments and Assume lines (an Assume there is what skipped the test)."""
    for call in re.findall(r"\b(\w+)\s*\(", "\n".join(method_body(lines, start)[1:])):
        if call == method:
            continue
        pat = re.compile(r"^\s*(private|protected|public)?\s*(static\s+)?[\w<>\[\]]+\s+%s\s*\(" % re.escape(call))
        for h, line in enumerate(lines):
            if not pat.search(line):
                continue
            body = method_body(lines, h)
            assumes = [x.strip() for x in body if re.search(r"Assume\.|assume[A-Z]\w*\(", x)]
            if not assumes:
                break
            first = next(n for n, x in enumerate(body) if re.search(r"Assume\.|assume[A-Z]\w*\(", x))
            comments = [x.strip().lstrip("/").strip() for x in body[:first] if x.strip().startswith("//")]
            return "assumption in helper %s(): %s | %s" % (call, " ".join(comments), " | ".join(assumes))
    return None


def method_reason(path, method):
    if not path:
        return "source not found"
    with open(path, encoding="utf-8", errors="replace") as fh:
        lines = fh.read().splitlines()
    method = re.sub(r"\[.*\]$", "", method)  # parameterized runs: name[0]
    pat = re.compile(r"\bvoid\s+%s\s*\(" % re.escape(method))
    for i, line in enumerate(lines):
        if not pat.search(line):
            continue
        above = []
        j = i - 1
        while j >= 0:
            s = lines[j].strip()
            if s.startswith("@") or s.startswith("//") or s.startswith("*") or s.startswith("/*"):
                above.insert(0, s)
                j -= 1
                continue
            break
        body = []
        depth = 0
        started = False
        for k in range(i, min(i + 80, len(lines))):
            depth += lines[k].count("{") - lines[k].count("}")
            if "{" in lines[k]:
                started = True
            if re.search(r"Assume\.|assume[A-Z]\w*\(|OS\.IS_", lines[k]):
                body.append(lines[k].strip())
            if started and depth <= 0:
                break
        reasons = [a for a in above if not a.startswith("@Test")] + body
        reasons += class_level_reason(lines)
        if reasons:
            return " | ".join(reasons)
        helper = helper_reason(lines, i, method)
        if helper:
            return helper
        return "no annotation found (inherited/assumption in helper)"
    cls = class_level_reason(lines)
    if cls:
        return " | ".join(cls)
    return "method defined in a superclass of %s" % os.path.basename(path)


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    grand = [0, 0, 0, 0]
    skipped_rows = []
    failed_rows = []
    reports = sorted(glob.glob(os.path.join(root, "**/target/surefire-reports/TEST-*.xml"), recursive=True))
    per_module = {}
    for rep in reports:
        module_dir = rep.split("/target/surefire-reports/")[0]
        try:
            tree = ET.parse(rep)
        except ET.ParseError as exc:
            print("WARN: cannot parse %s: %s" % (rep, exc))
            continue
        suite = tree.getroot()
        counts = per_module.setdefault(os.path.relpath(module_dir, root), [0, 0, 0, 0])
        for case in suite.iter("testcase"):
            counts[0] += 1
            cls = case.get("classname", "")
            name = case.get("name", "")
            if case.find("failure") is not None:
                counts[1] += 1
                failed_rows.append("%s#%s (failure)" % (cls, name))
            elif case.find("error") is not None:
                counts[2] += 1
                failed_rows.append("%s#%s (error)" % (cls, name))
            elif case.find("skipped") is not None:
                counts[3] += 1
                msg = (case.find("skipped").get("message") or "").strip()
                if not msg:
                    msg = method_reason(find_source(module_dir, cls), name)
                skipped_rows.append("SKIPPED %s#%s -- reason: %s" % (cls, name, msg))
    print("=" * 78)
    print("SEAL SUREFIRE SUMMARY (%d report files)" % len(reports))
    print("=" * 78)
    for mod, c in sorted(per_module.items()):
        print("%-55s run=%5d fail=%3d err=%3d skip=%3d pass=%5d" % (mod, c[0], c[1], c[2], c[3], c[0] - c[1] - c[2] - c[3]))
        for i in range(4):
            grand[i] += c[i]
    print("-" * 78)
    print("TOTAL tests run=%d, failures=%d, errors=%d, skipped=%d, passed=%d"
          % (grand[0], grand[1], grand[2], grand[3], grand[0] - grand[1] - grand[2] - grand[3]))
    if skipped_rows:
        print("\nSkipped tests and their reasons:")
        for row in skipped_rows:
            print("  " + row)
    if failed_rows:
        print("\nFailed tests:")
        for row in failed_rows:
            print("  " + row)
    if grand[0] == 0:
        print("ERROR: no surefire reports found")
        return 1
    return 1 if failed_rows else 0


if __name__ == "__main__":
    sys.exit(main())
