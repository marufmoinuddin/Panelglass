#!/usr/bin/env python3
"""Builds the unit-test report: one self-contained HTML page and a Markdown summary for a pull request.

Reads what Gradle left behind, nothing else:
  - JUnit XML results:  <module>/build/test-results/<task>/TEST-*.xml
  - JaCoCo CSV reports: <module>/build/reports/jacoco/jacocoUnitTestReport/jacocoUnitTestReport.csv
  - the test sources, for each test's expected behaviour (its KDoc, else its name read as a sentence)

For every test: Expected (what the test says the code must do), Actual (what this run observed: every assertion
held, or the assertion's own expected/actual values and message), and the verdict PASS / FAIL / SKIP.

Standard library only. Usage:
  python3 tools/test-report/report.py --root . --out build/test-report \
      [--title ...] [--commit SHA] [--run-url URL] [--report-url URL]
Exit code: 0 when every test passed, 1 when a test failed or no result was found, 2 on a usage error.
"""

import argparse
import csv
import datetime
import html
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

MODULE_GLOBS = ("app", "core", "feature")


# ---- discovery -----------------------------------------------------------------------------------------------------

def module_dirs(root):
    """`app`, `core/<x>`, `feature/<x>`: every Gradle module directory with a build file."""
    out = []
    for top in MODULE_GLOBS:
        base = os.path.join(root, top)
        if os.path.isfile(os.path.join(base, "build.gradle.kts")):
            out.append(top)
            continue
        if not os.path.isdir(base):
            continue
        for d in sorted(os.listdir(base)):
            if os.path.isfile(os.path.join(base, d, "build.gradle.kts")):
                out.append(f"{top}/{d}")
    return out


def result_files(root, module):
    base = os.path.join(root, module, "build", "test-results")
    found = []
    if not os.path.isdir(base):
        return found
    for task in sorted(os.listdir(base)):
        d = os.path.join(base, task)
        if not os.path.isdir(d):
            continue
        for f in sorted(os.listdir(d)):
            if f.startswith("TEST-") and f.endswith(".xml"):
                found.append((task, os.path.join(d, f)))
    return found


def source_index(root, module):
    """Fully qualified test class name -> its source text (Kotlin or Java under src/test)."""
    index = {}
    for lang in ("kotlin", "java"):
        base = os.path.join(root, module, "src", "test", lang)
        for dirpath, _, files in os.walk(base):
            for f in files:
                if not f.endswith((".kt", ".java")):
                    continue
                path = os.path.join(dirpath, f)
                try:
                    text = open(path, encoding="utf-8").read()
                except OSError:
                    continue
                pkg = re.search(r"^\s*package\s+([\w.]+)", text, re.M)
                prefix = pkg.group(1) + "." if pkg else ""
                for m in re.finditer(r"^\s*(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:private|internal|public|open|abstract|data)\s+)*class\s+(\w+)", text, re.M):
                    index.setdefault(prefix + m.group(1), (os.path.relpath(path, root).replace(os.sep, "/"), text))
    return index


# ---- expected behaviour from the source ----------------------------------------------------------------------------

def clean_kdoc(block):
    lines = []
    for line in block.splitlines():
        line = line.strip()
        line = re.sub(r"^/\*\*", "", line)
        line = re.sub(r"\*/$", "", line)
        line = re.sub(r"^\*\s?", "", line)
        lines.append(line.strip())
    text = " ".join(l for l in lines if l)
    text = re.sub(r"\[([^\]]+)\]", r"\1", text)  # [Symbol] links read as plain names
    return text.strip()


def kdoc_before(text, start):
    """The KDoc that ends right before [start], annotations and blank lines allowed in between, else None."""
    head = text[:start].rstrip()
    while True:
        m = re.search(r"(@[\w.]+(?:\([^()]*\))?)\s*$", head)
        if not m:
            break
        head = head[: m.start()].rstrip()
    if head.endswith("*/"):
        open_at = head.rfind("/**")
        if open_at >= 0:
            return clean_kdoc(head[open_at:])
    return None


def humanize(name):
    """`aChecksumMismatchFailsTheDownload` -> 'A checksum mismatch fails the download.'"""
    name = name.strip("`")
    if " " in name:
        sentence = name
    else:
        name = name.replace("_", " ")
        words = re.findall(r"[A-Z]{2,}(?=[A-Z][a-z]|\b|\d)|[A-Z]?[a-z]+|[A-Z]+|\d+", name)
        sentence = " ".join(w if (w.isupper() and len(w) > 1) else w.lower() for w in words)
    sentence = sentence.strip()
    return (sentence[:1].upper() + sentence[1:] + ".") if sentence else name


def expected_for(src_text, method):
    base = method[:-2] if method.endswith("()") else method
    base = re.sub(r"\[\d+\].*$", "", base).strip()
    if src_text:
        m = re.search(r"fun\s+`?" + re.escape(base) + r"`?\s*\(", src_text)
        if m:
            line_start = src_text.rfind("\n", 0, m.start()) + 1
            doc = kdoc_before(src_text, line_start) or kdoc_before(src_text, m.start())
            if doc:
                return doc, humanize(base)
    return None, humanize(base)


def class_doc(src_text, simple):
    if not src_text:
        return None
    m = re.search(r"^(?:\s*@[\w.]+(?:\([^()]*\))?\s*)*\s*(?:(?:private|internal|public|open|abstract)\s+)*class\s+" + re.escape(simple) + r"\b", src_text, re.M)
    return kdoc_before(src_text, m.start()) if m else None


# ---- actual outcome from the result ---------------------------------------------------------------------------------

EXPECTED_ACTUAL = [
    re.compile(r"expected:\s*<(?P<e>.*?)>\s*but was:\s*<(?P<a>.*?)>", re.S),
    re.compile(r"expected \[(?P<e>.*?)\] but (?:was|found) \[(?P<a>.*?)\]", re.S),
]


def outcome(case):
    """(verdict, actual text, expected value or None, actual value or None, details)."""
    failure = case.find("failure")
    error = case.find("error")
    skipped = case.find("skipped")
    time = float(case.get("time") or 0)
    if failure is None and error is None:
        if skipped is not None:
            why = (skipped.get("message") or "").strip()
            return "SKIP", "Not run" + (f": {why}" if why else "."), None, None, ""
        return "PASS", f"As expected: every assertion held ({time:.2f} s).", None, None, ""
    node = failure if failure is not None else error
    message = (node.get("message") or "").strip()
    kind = node.get("type") or ""
    details = (node.text or "").strip()
    exp_val = act_val = None
    for rx in EXPECTED_ACTUAL:
        m = rx.search(message) or rx.search(details[:4000])
        if m:
            exp_val, act_val = m.group("e"), m.group("a")
            break
    short_kind = kind.rsplit(".", 1)[-1] if kind else "Failure"
    lead = message.split("\n", 1)[0] if message else ""
    # JUnit often repeats the exception's class in its message ("java.io.IOException: …"): say it once.
    lead = re.sub(r"^(?:[\w$]+\.)+" + re.escape(short_kind) + r":\s*", "", lead) if kind else lead
    lead = lead[:500] + ("…" if len(lead) > 500 else "")
    if exp_val is not None:
        actual = f"Got {act_val!r} where {exp_val!r} was expected."
        prefix = re.split(r"(?:==>\s*)?expected", lead, maxsplit=1)[0].strip(" :=>")
        if prefix and not prefix.lower().startswith("org.") and len(prefix) < 200:
            actual += f" ({prefix})"
    elif lead:
        actual = f"{short_kind}: {lead}"
    else:
        actual = short_kind
    return "FAIL", actual, exp_val, act_val, details


# ---- coverage ---------------------------------------------------------------------------------------------------------

COUNTERS = ("im", "ic", "bm", "bc", "lm", "lc")
PARTS = ("logic", "ui")


def composable_files(root, module):
    """(package, file name) of every main source file that declares a @Composable function."""
    found = set()
    for lang in ("kotlin", "java"):
        base = os.path.join(root, module, "src", "main", lang)
        for dirpath, _, files in os.walk(base):
            for f in files:
                if not f.endswith(".kt"):
                    continue
                try:
                    text = open(os.path.join(dirpath, f), encoding="utf-8").read()
                except OSError:
                    continue
                if "@Composable" not in text:
                    continue
                pkg = re.search(r"^\s*package\s+([\w.]+)", text, re.M)
                found.add((pkg.group(1) if pkg else "", f))
    return found


def is_ui(row, ui_files):
    """Compose UI: the file class of a file with @Composable functions (`FooKt`, its lambdas `FooKt.Bar.1…`, and
    `ComposableSingletons$FooKt`). A class of its own is logic, even in such a file (a view model next to its screen)."""
    top = row["CLASS"].split(".", 1)[0].rsplit("$", 1)[-1]
    return top.endswith("Kt") and (row["PACKAGE"], top[:-2] + ".kt") in ui_files


def coverage(root, module):
    """The module's JaCoCo counters, overall and split into logic and Compose UI (`logic` / `ui`)."""
    path = os.path.join(root, module, "build", "reports", "jacoco", "jacocoUnitTestReport", "jacocoUnitTestReport.csv")
    if not os.path.isfile(path):
        return None
    ui_files = composable_files(root, module)
    sums = dict.fromkeys(COUNTERS, 0)
    split = {part: dict.fromkeys(COUNTERS, 0) for part in PARTS}
    with open(path, encoding="utf-8") as f:
        for row in csv.DictReader(f):
            values = dict(
                im=int(row["INSTRUCTION_MISSED"]), ic=int(row["INSTRUCTION_COVERED"]),
                bm=int(row["BRANCH_MISSED"]), bc=int(row["BRANCH_COVERED"]),
                lm=int(row["LINE_MISSED"]), lc=int(row["LINE_COVERED"]),
            )
            part = split["ui" if is_ui(row, ui_files) else "logic"]
            for k, v in values.items():
                sums[k] += v
                part[k] += v
    sums.update(split)
    return sums


def pct(covered, missed):
    total = covered + missed
    return None if total == 0 else 100.0 * covered / total


def fmt_pct(v):
    return "–" if v is None else f"{v:.1f}%"


def lines_pct(c, part=None):
    """Line coverage of counters [c] (a module's or the totals), or of one part of them ("logic" / "ui")."""
    c = (c or {}).get(part) if part else c
    return fmt_pct(pct(c.get("lc", 0), c.get("lm", 0))) if c else "–"


def branches_pct(c, part=None):
    c = (c or {}).get(part) if part else c
    return fmt_pct(pct(c.get("bc", 0), c.get("bm", 0))) if c else "–"


# ---- model ------------------------------------------------------------------------------------------------------------

def collect(root):
    modules = []
    for module in module_dirs(root):
        files = result_files(root, module)
        cov = coverage(root, module)
        if not files and cov is None:
            continue
        index = source_index(root, module)
        classes = {}
        for task, path in files:
            try:
                suite = ET.parse(path).getroot()
            except ET.ParseError as e:
                classes.setdefault(path, dict(name=os.path.basename(path), doc=None, source=None, tests=[]))["tests"].append(
                    dict(name="(unreadable result file)", expected="A readable JUnit XML file.", verdict="FAIL",
                         actual=f"ParseError: {e}", exp_val=None, act_val=None, details="", time=0.0, task=task))
                continue
            for case in suite.iter("testcase"):
                cls = case.get("classname") or suite.get("name") or "?"
                method = case.get("name") or "?"
                source, text = index.get(cls, (None, None))
                c = classes.setdefault(cls, dict(name=cls, doc=class_doc(text, cls.rsplit(".", 1)[-1]), source=source, tests=[]))
                doc, human = expected_for(text, method)
                verdict, actual, exp_val, act_val, details = outcome(case)
                c["tests"].append(dict(
                    name=method[:-2] if method.endswith("()") else method, expected=doc or human, verdict=verdict, actual=actual,
                    exp_val=exp_val, act_val=act_val, details=details[:6000], time=float(case.get("time") or 0), task=task,
                ))
        tests = [t for c in classes.values() for t in c["tests"]]
        modules.append(dict(
            name=module, classes=sorted(classes.values(), key=lambda c: c["name"]), coverage=cov,
            total=len(tests), passed=sum(t["verdict"] == "PASS" for t in tests),
            failed=sum(t["verdict"] == "FAIL" for t in tests), skipped=sum(t["verdict"] == "SKIP" for t in tests),
            time=sum(t["time"] for t in tests),
        ))
    return modules


def totals(modules):
    t = dict(total=0, passed=0, failed=0, skipped=0, time=0.0, **dict.fromkeys(COUNTERS, 0))
    for part in PARTS:
        t[part] = dict.fromkeys(COUNTERS, 0)
    for m in modules:
        for k in ("total", "passed", "failed", "skipped", "time"):
            t[k] += m[k]
        if m["coverage"]:
            for k in COUNTERS:
                t[k] += m["coverage"][k]
                for part in PARTS:
                    t[part][k] += m["coverage"][part][k]
    return t


# ---- HTML -------------------------------------------------------------------------------------------------------------

CSS = """
:root{--bg:#fbfaf7;--card:#ffffff;--ink:#1d1d1f;--soft:#5d5d66;--line:#e4e2dc;--pass:#1d7a46;--pass-bg:#e5f4ea;
--fail:#b42318;--fail-bg:#fdecea;--skip:#8a6d00;--skip-bg:#fff6d6;--accent:#2952cc;--code:#f3f1ec}
@media (prefers-color-scheme:dark){:root{--bg:#141416;--card:#1d1d21;--ink:#ececf1;--soft:#a3a3ad;--line:#33333a;
--pass:#5fd394;--pass-bg:#173525;--fail:#ff8a7a;--fail-bg:#3d1b18;--skip:#f3cd4d;--skip-bg:#3a3214;--accent:#8fa8ff;--code:#26262b}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:14px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
main{max-width:1180px;margin:0 auto;padding:24px 16px 64px}h1{font-size:24px;margin:0 0 4px}h2{font-size:17px;margin:28px 0 10px}
.meta{color:var(--soft);font-size:13px}.meta a{color:var(--accent)}
.verdict{display:inline-block;font-weight:700;letter-spacing:.04em;padding:6px 14px;border-radius:999px;margin:14px 0 6px}
.verdict.PASS{background:var(--pass-bg);color:var(--pass)}.verdict.FAIL{background:var(--fail-bg);color:var(--fail)}
.tiles{display:grid;grid-template-columns:repeat(auto-fit,minmax(140px,1fr));gap:10px;margin:14px 0}
.tile{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:12px 14px}
.tile b{display:block;font-size:22px}.tile span{color:var(--soft);font-size:12px}
table{width:100%;border-collapse:collapse;background:var(--card);border:1px solid var(--line);border-radius:12px;overflow:hidden}
th,td{text-align:left;padding:8px 10px;border-top:1px solid var(--line);vertical-align:top}th{background:var(--code);font-size:12px;color:var(--soft);border-top:0}
td.num,th.num{text-align:right;white-space:nowrap}
.badge{font-size:11px;font-weight:700;padding:2px 8px;border-radius:999px;white-space:nowrap}
.badge.PASS{background:var(--pass-bg);color:var(--pass)}.badge.FAIL{background:var(--fail-bg);color:var(--fail)}.badge.SKIP{background:var(--skip-bg);color:var(--skip)}
details.module{margin:12px 0;background:var(--card);border:1px solid var(--line);border-radius:12px}
details.module>summary{cursor:pointer;padding:12px 14px;font-weight:600;display:flex;gap:10px;flex-wrap:wrap;align-items:center}
details.module>summary .counts{color:var(--soft);font-weight:400;font-size:13px}
.cls{padding:0 14px 14px}.cls h3{font-size:14px;margin:14px 0 2px;word-break:break-all}.cls .doc{color:var(--soft);margin:0 0 8px;font-size:13px}
.cls .src{font-size:12px;color:var(--soft)}
.cls table td:first-child{width:22%;word-break:break-word;font-family:ui-monospace,Consolas,monospace;font-size:12.5px}
.cls table td:nth-child(2),.cls table td:nth-child(3){width:34%}
.vals{margin-top:6px;font-family:ui-monospace,Consolas,monospace;font-size:12px;background:var(--code);padding:6px 8px;border-radius:8px;white-space:pre-wrap;word-break:break-word}
pre.trace{max-height:260px;overflow:auto;font-size:11.5px;background:var(--code);padding:8px;border-radius:8px;white-space:pre-wrap;word-break:break-word}
.controls{display:flex;flex-wrap:wrap;gap:8px;align-items:center;margin:8px 0 4px}
.controls button{border:1px solid var(--line);background:var(--card);color:var(--ink);padding:6px 12px;border-radius:999px;cursor:pointer;font:inherit}
.controls button[aria-pressed=true]{border-color:var(--accent);color:var(--accent);font-weight:600}
.controls input{flex:1;min-width:180px;border:1px solid var(--line);background:var(--card);color:var(--ink);padding:7px 12px;border-radius:999px;font:inherit}
.note{color:var(--soft);font-size:13px}.hidden{display:none}
@media (max-width:640px){.cls table,.cls thead,.cls tbody,.cls tr,.cls td{display:block;width:100%!important}.cls thead{display:none}
.cls tr{border-top:1px solid var(--line);padding:6px 0}.cls td{border:0;padding:4px 10px}}
"""

JS = """
(function(){var f='ALL',q='';var rows=[].slice.call(document.querySelectorAll('tr.t'));
function apply(){rows.forEach(function(r){var ok=(f==='ALL'||r.dataset.v===f)&&(!q||r.dataset.s.indexOf(q)>=0);r.classList.toggle('hidden',!ok)});
[].forEach.call(document.querySelectorAll('.cls'),function(c){c.classList.toggle('hidden',!c.querySelector('tr.t:not(.hidden)'))});
[].forEach.call(document.querySelectorAll('details.module'),function(d){var any=d.querySelector('tr.t:not(.hidden)');d.classList.toggle('hidden',!any);if((f!=='ALL'||q)&&any)d.open=true})}
[].forEach.call(document.querySelectorAll('[data-filter]'),function(b){b.addEventListener('click',function(){f=b.dataset.filter;
[].forEach.call(document.querySelectorAll('[data-filter]'),function(x){x.setAttribute('aria-pressed',x===b)});apply()})});
var s=document.getElementById('q');s.addEventListener('input',function(){q=s.value.toLowerCase();apply()});})();
"""


def e(s):
    return html.escape(str(s), quote=True)


def render_html(modules, t, args):
    verdict = "PASS" if t["failed"] == 0 and t["total"] > 0 else "FAIL"
    now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    meta = [f"Generated {e(now)}"]
    if args.commit:
        meta.append(f"commit <code>{e(args.commit[:12])}</code>")
    if args.run_url:
        meta.append(f'<a href="{e(args.run_url)}">workflow run</a>')
    out = [f"<!doctype html><html lang='en'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>",
           f"<title>{e(args.title)}</title><style>{CSS}</style></head><body><main>",
           f"<h1>{e(args.title)}</h1><div class='meta'>{' · '.join(meta)}</div>",
           f"<div class='verdict {verdict}'>{'PASS' if verdict == 'PASS' else 'FAIL'}: {t['passed']} of {t['total']} tests passed"
           + (f", {t['failed']} failed" if t['failed'] else "") + (f", {t['skipped']} skipped" if t['skipped'] else "") + "</div>",
           "<div class='tiles'>",
           f"<div class='tile'><b>{t['total']}</b><span>tests run</span></div>",
           f"<div class='tile'><b style='color:var(--pass)'>{t['passed']}</b><span>passed</span></div>",
           f"<div class='tile'><b style='color:var(--fail)'>{t['failed']}</b><span>failed</span></div>",
           f"<div class='tile'><b>{t['skipped']}</b><span>skipped</span></div>",
           f"<div class='tile'><b>{lines_pct(t)}</b><span>line coverage, all code</span></div>",
           f"<div class='tile'><b>{lines_pct(t, 'logic')}</b><span>line coverage, logic</span></div>",
           f"<div class='tile'><b>{lines_pct(t, 'ui')}</b><span>line coverage, Compose UI</span></div>",
           "</div>",
           "<p class='note'>Expected is what each test states the code must do (its KDoc, else its name read as a sentence). "
           "Actual is what this run observed: every assertion held, or the failed assertion's own values. JVM unit tests only: "
           "the instrumented tests need a device and are not part of this report.</p>",
           "<h2>Coverage</h2><table><thead><tr><th>Code</th><th class='num'>Lines covered</th><th class='num'>Lines</th>"
           "<th class='num'>Branches</th></tr></thead><tbody>"]
    for label, part in (("All code", None), ("Logic", "logic"), ("Compose UI", "ui")):
        c = t[part] if part else t
        out.append(f"<tr><td>{label}</td><td class='num'>{c['lc']:,} of {c['lc'] + c['lm']:,}</td>"
                   f"<td class='num'>{lines_pct(c)}</td><td class='num'>{branches_pct(c)}</td></tr>")
    out.append("</tbody></table><p class='note'>Compose UI is the code of the files that declare <code>@Composable</code> "
               "functions (their file classes and lambdas); everything else, view models included, is logic. The Compose "
               "compiler adds branches of its own to every composable, so UI branch coverage reads low even where every "
               "control is tested.</p>")
    out.append("<h2>By module</h2><table><thead><tr><th>Module</th><th class='num'>Tests</th><th class='num'>Passed</th>"
               "<th class='num'>Failed</th><th class='num'>Skipped</th><th class='num'>Time</th><th class='num'>Lines</th>"
               "<th class='num'>Logic</th><th class='num'>UI</th><th class='num'>Branches</th><th>Verdict</th></tr></thead><tbody>")
    for m in modules:
        c = m["coverage"]
        mv = "PASS" if m["failed"] == 0 and m["total"] > 0 else ("FAIL" if m["failed"] else "SKIP")
        label = "PASS" if mv == "PASS" else ("FAIL" if mv == "FAIL" else "NO TESTS")
        out.append(f"<tr><td>{e(m['name'])}</td><td class='num'>{m['total']}</td><td class='num'>{m['passed']}</td>"
                   f"<td class='num'>{m['failed']}</td><td class='num'>{m['skipped']}</td><td class='num'>{m['time']:.1f} s</td>"
                   f"<td class='num'>{lines_pct(c)}</td><td class='num'>{lines_pct(c, 'logic')}</td><td class='num'>{lines_pct(c, 'ui')}</td>"
                   f"<td class='num'>{branches_pct(c)}</td><td><span class='badge {mv}'>{label}</span></td></tr>")
    out.append("</tbody></table>")
    out.append("<h2>Every test</h2><div class='controls'>"
               "<button data-filter='ALL' aria-pressed='true'>All</button><button data-filter='FAIL' aria-pressed='false'>Failed</button>"
               "<button data-filter='PASS' aria-pressed='false'>Passed</button><button data-filter='SKIP' aria-pressed='false'>Skipped</button>"
               "<input id='q' type='search' placeholder='Filter by class, test or text' aria-label='Filter tests'></div>")
    for m in modules:
        if not m["total"]:
            continue
        opened = " open" if m["failed"] else ""
        out.append(f"<details class='module'{opened}><summary>{e(m['name'])}<span class='counts'>{m['passed']}/{m['total']} passed"
                   + (f" · {m['failed']} failed" if m['failed'] else "") + "</span></summary>")
        for c in m["classes"]:
            simple = c["name"].rsplit(".", 1)[-1]
            out.append(f"<div class='cls'><h3>{e(simple)}</h3>")
            if c["doc"]:
                out.append(f"<p class='doc'>{e(c['doc'])}</p>")
            if c["source"]:
                out.append(f"<div class='src'>{e(c['source'])}</div>")
            out.append("<table><thead><tr><th>Test</th><th>Expected</th><th>Actual</th><th>Verdict</th></tr></thead><tbody>")
            for tc in c["tests"]:
                search = f"{c['name']} {tc['name']} {tc['expected']} {tc['actual']}".lower()
                vals = ""
                if tc["exp_val"] is not None:
                    vals = f"<div class='vals'>expected: {e(tc['exp_val'])}\nactual:   {e(tc['act_val'])}</div>"
                trace = f"<details><summary class='note'>Stack trace</summary><pre class='trace'>{e(tc['details'])}</pre></details>" if tc["details"] else ""
                out.append(f"<tr class='t' data-v='{tc['verdict']}' data-s='{e(search)}'><td>{e(tc['name'])}</td><td>{e(tc['expected'])}</td>"
                           f"<td>{e(tc['actual'])}{vals}{trace}</td><td><span class='badge {tc['verdict']}'>{tc['verdict']}</span>"
                           f"<div class='note'>{tc['time']:.2f} s</div></td></tr>")
            out.append("</tbody></table></div>")
        out.append("</details>")
    out.append(f"</main><script>{JS}</script></body></html>")
    return "\n".join(out)


# ---- Markdown (pull request comment) ---------------------------------------------------------------------------------

MARKER = "<!-- panelglass-test-report -->"


def render_markdown(modules, t, args, max_failures=25):
    verdict = "PASS" if t["failed"] == 0 and t["total"] > 0 else "FAIL"
    icon = "✅" if verdict == "PASS" else "❌"
    lines = [MARKER, f"## {icon} Unit tests: {verdict}", ""]
    lines.append(f"**{t['passed']} of {t['total']} passed**" + (f", **{t['failed']} failed**" if t["failed"] else "")
                 + (f", {t['skipped']} skipped" if t["skipped"] else "")
                 + f" · line coverage {lines_pct(t)} (logic {lines_pct(t, 'logic')}, Compose UI {lines_pct(t, 'ui')})"
                 + f" · branch coverage {branches_pct(t)}")
    links = []
    if args.report_url:
        links.append(f"[Full HTML report (expected vs actual for every test)]({args.report_url})")
    if args.run_url:
        links.append(f"[Workflow run]({args.run_url})")
    if links:
        lines += ["", " · ".join(links)]
    lines += ["", "| Module | Tests | Passed | Failed | Skipped | Lines | Logic | UI | Branches |", "|---|---:|---:|---:|---:|---:|---:|---:|---:|"]
    for m in modules:
        c = m["coverage"]
        lines.append(f"| `{m['name']}` | {m['total']} | {m['passed']} | {m['failed']} | {m['skipped']} | "
                     f"{lines_pct(c)} | {lines_pct(c, 'logic')} | {lines_pct(c, 'ui')} | {branches_pct(c)} |")
    failures = [(m["name"], c["name"].rsplit(".", 1)[-1], tc) for m in modules for c in m["classes"] for tc in c["tests"] if tc["verdict"] == "FAIL"]
    if failures:
        lines += ["", "### Failed tests", "", "| Test | Expected | Actual |", "|---|---|---|"]
        for mod, cls, tc in failures[:max_failures]:
            cell = lambda s: str(s).replace("|", "\\|").replace("\n", " ")[:300]
            lines.append(f"| `{cls}.{tc['name']}` | {cell(tc['expected'])} | {cell(tc['actual'])} |")
        if len(failures) > max_failures:
            lines.append(f"| … | {len(failures) - max_failures} more in the HTML report | |")
    if t["total"] == 0:
        lines += ["", "No test results were found: the test task did not run or did not finish."]
    lines += ["", "<sub>JVM unit tests (`testDebugUnitTest`, `:core:model:test`). Instrumented tests need a device and are not run in CI. "
                  "Compose UI = files declaring `@Composable` functions; the rest, view models included, is logic.</sub>"]
    if args.commit:
        lines.append(f"<sub>Commit {args.commit[:12]}.</sub>")
    return "\n".join(lines) + "\n"


# ---- main -------------------------------------------------------------------------------------------------------------

def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--root", default=".")
    p.add_argument("--out", default="build/test-report")
    p.add_argument("--title", default="Panelglass unit-test report")
    p.add_argument("--commit", default="")
    p.add_argument("--run-url", default="")
    p.add_argument("--report-url", default="", help="where the HTML report can be opened (for the Markdown summary)")
    args = p.parse_args(argv)
    root = os.path.abspath(args.root)
    if not os.path.isdir(root):
        print(f"no such directory: {root}", file=sys.stderr)
        return 2
    modules = collect(root)
    t = totals(modules)
    os.makedirs(args.out, exist_ok=True)
    with open(os.path.join(args.out, "test-report.html"), "w", encoding="utf-8") as f:
        f.write(render_html(modules, t, args))
    with open(os.path.join(args.out, "summary.md"), "w", encoding="utf-8") as f:
        f.write(render_markdown(modules, t, args))
    verdict = "PASS" if t["failed"] == 0 and t["total"] > 0 else "FAIL"
    with open(os.path.join(args.out, "results.json"), "w", encoding="utf-8") as f:
        json.dump(dict(verdict=verdict, totals=t, modules=[{k: v for k, v in m.items() if k != "classes"} for m in modules],
                       tests=[dict(module=m["name"], cls=c["name"], **{k: tc[k] for k in ("name", "expected", "actual", "verdict", "time")})
                              for m in modules for c in m["classes"] for tc in c["tests"]]), f, indent=1)
    print(f"{verdict}: {t['passed']}/{t['total']} passed, {t['failed']} failed, {t['skipped']} skipped -> {args.out}")
    return 0 if verdict == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
