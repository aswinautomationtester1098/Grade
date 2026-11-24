#!/usr/bin/env python3
"""Build Grade's daily briefing (web/news.json).

Runs every morning in GitHub Actions. Uses only the Python standard library.
- Pulls fresh headlines from Google News RSS searches (no API key needed).
- Keeps only items from the last ~36 hours and removes duplicates.
- If the ANTHROPIC_API_KEY secret is set, asks Claude for a crisp one-line
  summary and a "why it matters" line for each item. Without the key the
  headline and source are shown as-is.
The app shows the briefing only on the date stamped in the file, so
yesterday's news disappears automatically.
"""
import datetime as dt
import email.utils
import html
import json
import os
import re
import sys
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

TZ = dt.timezone(dt.timedelta(hours=4))  # Asia/Dubai, no DST
OUT = os.path.join(os.path.dirname(__file__), "..", "web", "news.json")
UA = "Mozilla/5.0 (GradeBriefing/1.0; +https://github.com)"


def gnews(query, region="AE"):
    lang = {"AE": "en-AE", "IN": "en-IN", "US": "en-US"}[region]
    q = urllib.parse.quote(query)
    return f"https://news.google.com/rss/search?q={q}&hl={lang}&gl={region}&ceid={region}:en"


def gtopic(topic, region):
    lang = {"AE": "en-AE", "IN": "en-IN", "US": "en-US"}[region]
    return f"https://news.google.com/rss/headlines/section/topic/{topic}?hl={lang}&gl={region}&ceid={region}:en"


# Relevance filter for the QA section: "Playwright" is also a theatre word, so require software context.
QA_YES = re.compile(r"software|testing|tester|\bqa\b|quality (assurance|engineering)|test automation|automated test|sdet|"
                    r"\bbugs?\b|outage|devops|ci/cd|selenium|cypress|appium|jmeter|unit test|regression|test(ing)? tool|"
                    r"code review|vulnerab|release|deployment|observability", re.I)
QA_NO = re.compile(r"actress|actor|theat(re|er)|broadway|stage play|playwright(s)? (award|father|dad|mother)|novel|film|movie|"
                   r"maga|poet|drama|musical|oscar|celebrity|covid test|drug test|blood test|exam|students?\b", re.I)

# (id, title, how many, per-site limit, feeds, keep feed order (top stories) or sort by time, filter)
SECTIONS = [
    ("qa", "QA & Testing", 6, 2, [
        gnews('"software testing" OR "test automation" OR "QA automation" OR "software quality" OR "quality engineering" when:3d', "US"),
        gnews('(Playwright OR Selenium OR Cypress OR Appium) (testing OR "test automation" OR Microsoft OR framework) when:7d', "US"),
        gnews('software outage OR "testing failure" OR "software bug" root cause when:2d', "US"),
    ], False, "qa"),
    ("tech", "Tech & AI", 5, 1, [gtopic("TECHNOLOGY", "US")], True, None),
    ("uae", "UAE", 5, 1, [gtopic("NATION", "AE"),
                          gnews('UAE government OR law OR visa OR economy OR announces when:1d', "AE")], True, None),
    ("india", "India", 5, 1, [gtopic("NATION", "IN"),
                              gnews('India government OR economy OR RBI OR "Supreme Court" OR policy when:1d', "IN")], True, None),
    ("uaeindia", "UAE–India", 3, 1, [
        gnews('India UAE (visa OR remittance OR rupee OR dirham OR flights OR expats OR trade) when:3d', "AE"),
    ], False, None),
]

TAG = re.compile(r"<[^>]+>")


def clean(s):
    return re.sub(r"\s+", " ", html.unescape(TAG.sub(" ", s or ""))).strip()


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=25) as r:
        data = r.read(5_000_001)          # never accept more than 5 MB from a feed
    if len(data) > 5_000_000:
        raise ValueError("feed too large")
    return data


def parse(xml_bytes, now):
    items = []
    root = ET.fromstring(xml_bytes)
    for it in root.iter("item"):
        title = clean(it.findtext("title"))
        link = (it.findtext("link") or "").strip()
        src_el = it.find("source")
        source = clean(src_el.text) if src_el is not None and src_el.text else ""
        if source and title.endswith(" - " + source):
            title = title[: -len(source) - 3].strip()
        elif " - " in title and not source:
            title, source = title.rsplit(" - ", 1)
        desc = clean(it.findtext("description"))
        # Google News descriptions only repeat the headline; keep real summaries only
        if desc.lower().startswith(title.lower()[:40].lower()) or len(desc) < 40:
            desc = ""
        pub = it.findtext("pubDate")
        try:
            when = email.utils.parsedate_to_datetime(pub)
        except Exception:
            when = now
        if when.tzinfo is None:
            when = when.replace(tzinfo=dt.timezone.utc)
        if not re.match(r"^https?://", link):   # only ordinary web links reach the app
            continue
        items.append({"t": title[:200], "s": desc[:220], "src": source[:60], "u": link[:1000], "ts": when.isoformat()})
    return items


def norm(t):
    return re.sub(r"[^a-z0-9 ]", "", t.lower())[:70]


def words(t):
    return {w for w in re.findall(r"[a-z0-9]{3,}", t.lower())} - {"the", "and", "for", "with", "after", "from", "says", "new"}


def similar(a, b):
    """Same story from two outlets? Compare the headline words."""
    wa, wb = words(a), words(b)
    return bool(wa and wb) and len(wa & wb) / min(len(wa), len(wb)) >= 0.6


def ai_summaries(sections):
    key = os.environ.get("ANTHROPIC_API_KEY", "").strip()
    if not key:
        return
    flat = [(si, ii, it) for si, s in enumerate(sections) for ii, it in enumerate(s["items"])]
    if not flat:
        return
    listing = "\n".join(f"{n}. [{sections[si]['title']}] {it['t']} ({it['src']})" for n, (si, ii, it) in enumerate(flat))
    prompt = (
        "You write a daily briefing for a senior QA architect living in Dubai, originally from India. "
        "For each headline below, write: s = one crisp, neutral sentence (max 22 words) explaining what happened "
        "in plain English; w = why it matters to him (max 14 words). Do not invent facts beyond the headline; "
        "if unclear, describe it cautiously. Reply with only a JSON array of objects {\"n\":int,\"s\":str,\"w\":str}.\n\n"
        + listing
    )
    body = json.dumps({
        "model": os.environ.get("GRADE_MODEL", "claude-haiku-5-5"),
        "max_tokens": 4000,
        "messages": [{"role": "user", "content": prompt}],
    }).encode()
    req = urllib.request.Request(
        "https://api.anthropic.com/v1/messages", data=body,
        headers={"x-api-key": key, "anthropic-version": "2023-06-01", "content-type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            data = json.loads(r.read())
        text = "".join(b.get("text", "") for b in data.get("content", []))
        arr = json.loads(text[text.index("["): text.rindex("]") + 1])
        for o in arr:
            n = int(o["n"])
            if 0 <= n < len(flat):
                si, ii, it = flat[n]
                it["s"] = str(o.get("s", ""))[:240] or it["s"]
                it["w"] = str(o.get("w", ""))[:140]
        print(f"AI summaries added for {len(arr)} items")
    except Exception as e:  # never fail the briefing because of summaries
        print("AI summaries skipped:", e, file=sys.stderr)


def main():
    now = dt.datetime.now(dt.timezone.utc)
    cutoff = now - dt.timedelta(hours=36)
    seen, titles, out = set(), [], []
    for sid, title, limit, per_site, feeds, keep_order, filt in SECTIONS:
        pool = []
        for url in feeds:
            try:
                got = parse(fetch(url), now)
                print(f"[{sid}] feed returned {len(got)}")
                pool += got
            except Exception as e:
                print(f"[{sid}] feed failed: {e}", file=sys.stderr)
        window = now - dt.timedelta(days=7) if sid in ("qa", "uaeindia") else cutoff
        pool = [p for p in pool if dt.datetime.fromisoformat(p["ts"]) >= window and p["t"]]
        if filt == "qa":
            before = len(pool)
            pool = [p for p in pool if QA_YES.search(p["t"]) and not QA_NO.search(p["t"] + " " + p["src"])]
            print(f"[qa] relevance filter kept {len(pool)} of {before}")
        if not keep_order:
            pool.sort(key=lambda p: p["ts"], reverse=True)
        picked, per = [], {}
        for p in pool:
            k = norm(p["t"])
            if k in seen or any(similar(p["t"], t) for t in titles):
                continue
            if per.get(p["src"], 0) >= per_site:
                continue
            seen.add(k)
            titles.append(p["t"])
            per[p["src"]] = per.get(p["src"], 0) + 1
            picked.append(p)
            if len(picked) >= limit:
                break
        out.append({"id": sid, "title": title, "items": picked})
        print(f"[{sid}] {len(picked)} items")
    ai_summaries(out)
    total = sum(len(s["items"]) for s in out)
    if total == 0:
        print("No news fetched; keeping previous file", file=sys.stderr)
        sys.exit(0)
    doc = {"date": now.astimezone(TZ).strftime("%Y-%m-%d"), "generated": now.isoformat(), "tz": "Asia/Dubai", "sections": out}
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=1)
    print(f"Wrote {total} items for {doc['date']}")


if __name__ == "__main__":
    main()
