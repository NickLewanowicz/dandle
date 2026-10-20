#!/usr/bin/env python3
"""Render the repo's markdown (README, AGENTS, docs/) into a small static site
and serve it locally for human review.

    python3 scripts/serve-docs.py [--port 8777] [--build-only]

Output goes to .scratch/docs-site/ (gitignored). Relative .md links are
rewritten to .html; every other file (screenshots, design PNGs) is copied
through verbatim. Requires the `markdown` package — auto-installed into a
venv at .scratch/mdvenv on first run.
"""

import argparse
import html
import os
import re
import shutil
import subprocess
import sys
import venv
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SITE = ROOT / ".scratch" / "docs-site"
VENV = ROOT / ".scratch" / "mdvenv"
MD_FILES = ["README.md", "AGENTS.md"]
MD_DIRS = ["docs"]
EXTS = ("tables", "fenced_code", "sane_lists", "nl2br")

CSS = """body{font-family:-apple-system,'Segoe UI',Roboto,sans-serif;max-width:900px;
margin:2rem auto;padding:0 1rem;line-height:1.55;color:#1c1b1f}
img{max-width:100%;border:1px solid #ddd;border-radius:8px;margin:0.4rem 0}
pre{background:#f6f6f6;padding:0.8rem;border-radius:8px;overflow-x:auto}
code{background:#f2efe9;padding:0.1rem 0.3rem;border-radius:4px}
h1,h2,h3{line-height:1.2} table{border-collapse:collapse}
td,th{border:1px solid #ccc;padding:0.3rem 0.6rem}
.nav{font-size:0.85rem;margin-bottom:1.5rem} .nav a{margin-right:0.9rem}
.kicker{color:#777;font-size:0.85rem;margin-bottom:2rem}
li{margin:0.2rem 0}"""


def ensure_markdown() -> str:
    """Return the python executable that can `import markdown`."""
    try:
        import markdown  # noqa: F401

        return sys.executable
    except ImportError:
        if not (VENV / "bin" / "python").exists():
            print("building venv with markdown …")
            venv.create(VENV, with_pip=True)
            subprocess.run(
                [VENV / "bin" / "pip", "install", "-q", "markdown"], check=True
            )
        return str(VENV / "bin" / "python")


def rewrite_links(md_text: str) -> str:
    # [text](path.md) and [text](path.md#anchor) → .html; leave http(s)/images alone.
    return re.sub(
        r"\]\(([^)#\s]+?)\.md(#[^)\s]*)?\)",
        lambda m: f"]({m.group(1)}.html{m.group(2) or ''})",
        md_text,
    )


def render(py: str, src: Path, dst: Path, title: str, nav: str) -> None:
    text = rewrite_links(src.read_text(encoding="utf-8"))
    proc = subprocess.run(
        [py, "-c",
         "import sys,markdown;print(markdown.markdown(sys.stdin.read(),"
         f"extensions={EXTS!r}))"],
        input=text, capture_output=True, text=True,
    )
    if proc.returncode != 0:
        sys.exit(f"markdown render failed for {src}: {proc.stderr}")
    body = proc.stdout
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_text(
        f"<!doctype html><meta charset=utf-8>"
        f"<meta name=viewport content='width=device-width,initial-scale=1'>"
        f"<title>{html.escape(title)}</title><style>{CSS}</style>"
        f"<div class=nav>{nav}</div><h1>{html.escape(title)}</h1>{body}",
        encoding="utf-8",
    )


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8777)
    ap.add_argument("--build-only", action="store_true")
    args = ap.parse_args()

    if SITE.exists():
        shutil.rmtree(SITE)
    SITE.mkdir(parents=True)

    # Static assets: everything under docs/ that is not a .md (screenshots etc.).
    for d in MD_DIRS:
        shutil.copytree(ROOT / d, SITE / d,
                        ignore=shutil.ignore_patterns("*.md"))
    for extra in ("feed-sides",):  # (kept for future root assets)
        pass

    py = ensure_markdown()
    nav = "<a href='index.html'>Index</a> <a href='README.html'>README</a> " \
          "<a href='AGENTS.html'>Working agreement</a> " \
          "<a href='docs/flows/new-app/00-functional-spec.html'>Functional spec</a>"

    pages: list[tuple[str, Path]] = []
    targets = [ROOT / f for f in MD_FILES]
    for d in MD_DIRS:
        targets += sorted((ROOT / d).rglob("*.md"))
    for src in targets:
        rel = src.relative_to(ROOT)
        pages.append((str(rel), SITE / rel.with_suffix(".html")))
    for rel, dst in pages:
        title = rel + " — babybuddy-next"
        render(py, ROOT / rel, dst, title, nav)
        print(f"  {rel} → {dst.relative_to(SITE)}")

    index_items = "".join(
        f"<li><a href='{html.escape(rel)}.html'>{html.escape(rel)}</a></li>"
        for rel, _ in sorted(pages)
    )
    (SITE / "index.html").write_text(
        f"<!doctype html><meta charset=utf-8>"
        f"<meta name=viewport content='width=device-width,initial-scale=1'>"
        f"<title>babybuddy-next docs</title><style>{CSS}</style>"
        f"<h1>babybuddy-next — docs</h1><p class=kicker>Rendered from the "
        f"repo markdown; screenshots are the committed flow captures.</p>"
        f"<ul>{index_items}</ul>",
        encoding="utf-8",
    )
    print(f"site built: {SITE} ({len(pages)} pages)")

    if args.build_only:
        return
    print(f"serving on http://localhost:{args.port}/  (Ctrl-C to stop)")
    subprocess.run([sys.executable, "-m", "http.server", str(args.port),
                    "--bind", "127.0.0.1"], cwd=SITE)


if __name__ == "__main__":
    main()
