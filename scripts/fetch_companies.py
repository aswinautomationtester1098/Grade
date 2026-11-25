"""Collect public facts about the companies hiring in today's job files.

For each company we keep:
  * a short summary from Wikipedia (only when the page is clearly a company; CC BY-SA)
  * up to 4 recent news headlines from Google News (headline, source, link only)
  * simple risk flags derived from those headlines (layoffs, lawsuit, scam, ...)

Nothing is scraped from review sites; the app links to them instead.
Output: web/companies.json, keyed by normalised company name.
"""
import datetime as dt
import glob
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

WEB = os.path.join(os.path.dirname(__file__), "..", "web")
UA = "GradeApp/1.0 (+https://github.com/aswinautomationtester1098/Grade)"
MAX_COMPANIES = 90
BUDGET_SECONDS = 9 * 60

SUFFIX = re.compile(r"\b(inc|llc|ltd|limited|pvt|private|plc|gmbh|corp|corporation|co|company|sa|ag|bv|the)\b\.?", re.I)
COMPANY_WORDS = re.compile(r"\b(company|corporation|multinational|firm|startup|conglomerate|technology|software|"
                           r"services|bank|retailer|manufacturer|provider|platform|business|brand|agency)\b", re.I)
NOT_COMPANY = re.compile(r"may refer to|disambiguation|is a (?:surname|given name|village|town|city|river|species|film|"
                         r"novel|album|song|band)", re.I)
RISK = [
    ("layoffs", re.compile(r"\blay ?offs?\b|laid off|job cuts|cuts? jobs|redundanc|downsiz|workforce reduction", re.I)),
    ("legal", re.compile(r"\blawsuit|\bsued\b|fined|penalty|investigation|probe|regulator|fraud|settlement", re.I)),
    ("scam", re.compile(r"\bscam|fake job|fraudulent job|phishing|ponzi", re.I)),
    ("trouble", re.compile(r"bankrupt|insolven|shut(?:s|ting)? down|data breach|hacked|outage", re.I)),
]


def norm(name):
    n = SUFFIX.sub(" ", (name or "").lower())
    return re.sub(r"[^a-z0-9]+", "", n)


def get(url, timeout=20):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json, application/xml, */*"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read(1_000_000)


def wiki(name):
    """Return {text, url} when Wikipedia clearly has a company page for this name, else None."""
    q = urllib.parse.quote(name + " company")
    try:
        res = json.loads(get(f"https://en.wikipedia.org/w/rest.php/v1/search/page?q={q}&limit=3"))
    except Exception:
        return None
    want = norm(name)
    for p in res.get("pages", []):
        title = p.get("title", "")
        base = re.sub(r"\s*\(.*?\)\s*", "", title)
        if norm(base) != want:
            continue
        try:
            s = json.loads(get("https://en.wikipedia.org/api/rest_v1/page/summary/" + urllib.parse.quote(p["key"])))
        except Exception:
            continue
        text = (s.get("extract") or "").strip()
        if s.get("type") == "disambiguation" or NOT_COMPANY.search(text[:200]) or len(text) < 60:
            continue
        if not COMPANY_WORDS.search(text[:400]):
            continue
        text = re.sub(r"\s+", " ", text)
        if len(text) > 420:
            text = text[:420].rsplit(" ", 1)[0] + "…"
        url = (s.get("content_urls", {}).get("desktop", {}) or {}).get("page") or ""
        if not url.startswith("https://en.wikipedia.org/"):
            continue
        return {"text": text, "url": url}
    return None


def news(name):
    q = urllib.parse.quote(f'"{name}" when:90d')
    url = f"https://news.google.com/rss/search?q={q}&hl=en-US&gl=US&ceid=US:en"
    try:
        root = ET.fromstring(get(url))
    except Exception:
        return []
    out, seen = [], set()
    for it in root.iter("item"):
        t = re.sub(r"\s+", " ", (it.findtext("title") or "")).strip()
        link = (it.findtext("link") or "").strip()
        src = (it.findtext("source") or "").strip()
        if not t or not link.startswith("https://") or t.lower() in seen:
            continue
        if name.lower().split()[0] not in t.lower():     # headline must actually mention the company
            continue
        seen.add(t.lower())
        out.append({"t": t[:200], "u": link[:600], "src": src[:60]})
        if len(out) >= 4:
            break
    return out


def main():
    start = time.time()
    count = {}
    names = {}
    for fn in glob.glob(os.path.join(WEB, "jobs-*.json")):
        try:
            jobs = json.load(open(fn, encoding="utf-8")).get("jobs", [])
        except Exception:
            continue
        for j in jobs:
            c = (j.get("company") or "").strip()
            k = norm(c)
            if len(k) < 3 or c.lower() in ("confidential", "stealth startup", "undisclosed", "n/a"):
                continue
            count[k] = count.get(k, 0) + 1
            names.setdefault(k, c)
    top = sorted(count, key=lambda k: -count[k])[:MAX_COMPANIES]
    out = {}
    for k in top:
        if time.time() - start > BUDGET_SECONDS:
            print("time budget reached", file=sys.stderr)
            break
        name = names[k]
        entry = {"n": name, "jobs": count[k]}
        w = wiki(name)
        if w:
            entry["about"] = w
        time.sleep(0.4)
        items = news(name)
        time.sleep(1.2)
        if items:
            entry["news"] = items
            flags = [f for f, rx in RISK if any(rx.search(i["t"]) for i in items)]
            if flags:
                entry["flags"] = flags
        out[k] = entry
    doc = {"generated": dt.datetime.now(dt.timezone.utc).isoformat(), "companies": out}
    with open(os.path.join(WEB, "companies.json"), "w", encoding="utf-8") as fh:
        json.dump(doc, fh, ensure_ascii=False)
    print(f"companies: {len(out)} written, {sum(1 for v in out.values() if 'about' in v)} with summary, "
          f"{sum(1 for v in out.values() if 'news' in v)} with news")


if __name__ == "__main__":
    main()
