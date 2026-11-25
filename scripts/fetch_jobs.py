#!/usr/bin/env python3
"""Collect fresh QA / test automation openings into web/jobs.json (runs daily in GitHub Actions).

Sources are public job APIs that allow this kind of use with attribution and a link
back to the original posting. Every job keeps its original "apply" URL, so the app
sends you straight to the employer's or board's application page.
Matching against your resume happens on your phone, never here.
"""
import datetime as dt
import html
import json
import os
import re
import sys
import urllib.parse
import urllib.request

OUT = os.path.join(os.path.dirname(__file__), "..", "web", "jobs.json")
UA = "Mozilla/5.0 (GradeJobs/1.0; personal job alerts)"
QA = re.compile(r"\b(qa|q\.a\.|quality (assurance|engineer)|test(ing|er)?\b|sdet|automation engineer|test automation|quality engineering)", re.I)
TAG = re.compile(r"<[^>]+>")
# "QA" jobs that are not software testing (language, audio, content, manufacturing...)
NOT_SOFTWARE = re.compile(r"linguist|transcription|audio|video|translat|language|content (review|moderat)|annotat|"
                          r"rater|search quality|food|pharma|manufactur|clinical|medical device|construction|welding|"
                          r"mechanical|automotive quality|supplier quality|call quality|customer|thermal|propulsion|mechatronic|"
                          r"electrical test|electronics test|hardware test|\bhil\b|documentation technician|industrial|"
                          r"intern\b|working student|werkstudent|praktik", re.I)


def clean(s, n=2500):
    return re.sub(r"\s+", " ", html.unescape(TAG.sub(" ", s or ""))).strip()[:n]


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        data = r.read(15_000_001)         # cap each API response at 15 MB
    if len(data) > 15_000_000:
        raise ValueError("response too large")
    return json.loads(data)


def iso(v):
    try:
        if isinstance(v, (int, float)):
            return dt.datetime.fromtimestamp(v, dt.timezone.utc).isoformat()
        d = dt.datetime.fromisoformat(str(v).replace("Z", "+00:00").replace(" ", "T"))
        return (d if d.tzinfo else d.replace(tzinfo=dt.timezone.utc)).isoformat()
    except Exception:
        return dt.datetime.now(dt.timezone.utc).isoformat()


def remotive():
    seen = set()
    raw = []
    for q in ("category=qa", "search=qa%20engineer", "search=quality%20assurance", "search=test%20automation",
              "search=sdet", "search=playwright", "search=selenium", "search=software%20tester"):
        try:
            raw += get(f"https://remotive.com/api/remote-jobs?{q}&limit=100").get("jobs", [])
        except Exception as e:
            print(f"remotive {q} failed: {e}", file=sys.stderr)
    print(f"remotive raw: {len(raw)}")
    for j in raw:
        if j.get("id") in seen:
            continue
        seen.add(j.get("id"))
        yield dict(id="rm" + str(j.get("id")), title=j.get("title"), company=j.get("company_name"), logo=j.get("company_logo"),
                   loc=j.get("candidate_required_location") or "Remote", remote=True, salary=j.get("salary") or "",
                   type=(j.get("job_type") or "").replace("_", " "), tags=j.get("tags") or [], desc=clean(j.get("description")),
                   url=j.get("url"), posted=iso(j.get("publication_date")), source="Remotive")


def jobicy():
    raw, ids = [], set()
    for tag in ("qa", "testing", "automation", "sdet", "quality"):
        try:
            for j in get(f"https://jobicy.com/api/v2/remote-jobs?count=50&tag={tag}").get("jobs", []):
                if j.get("id") not in ids:
                    ids.add(j.get("id"))
                    raw.append(j)
        except Exception as e:
            print(f"jobicy {tag} failed: {e}", file=sys.stderr)
    print(f"jobicy raw: {len(raw)}")
    for j in raw:
        sal = ""
        if j.get("annualSalaryMin"):
            sal = f"{j.get('salaryCurrency', '')} {j['annualSalaryMin']:,}–{j.get('annualSalaryMax', j['annualSalaryMin']):,} / year".strip()
        yield dict(id="jc" + str(j.get("id")), title=clean(j.get("jobTitle")), company=j.get("companyName"), logo=j.get("companyLogo"),
                   loc=j.get("jobGeo") or "Remote", remote=True, salary=sal, type=", ".join(j.get("jobType") or []) if isinstance(j.get("jobType"), list) else (j.get("jobType") or ""),
                   tags=[j.get("jobLevel") or ""], desc=clean(j.get("jobDescription") or j.get("jobExcerpt")),
                   url=j.get("url"), posted=iso(j.get("pubDate")), source="Jobicy")


def arbeitnow():
    raw = []
    for page in (1, 2, 3, 4, 5):
        try:
            raw += get(f"https://www.arbeitnow.com/api/job-board-api?page={page}").get("data", [])
        except Exception as e:
            print(f"arbeitnow page {page} failed: {e}", file=sys.stderr)
            break
    print(f"arbeitnow raw: {len(raw)}")
    for j in raw:
        if not QA.search(j.get("title", "")):
            continue
        yield dict(id="an" + str(j.get("slug")), title=j.get("title"), company=j.get("company_name"), logo="",
                   loc=j.get("location") or "", remote=bool(j.get("remote")), salary="", type=", ".join(j.get("job_types") or []),
                   tags=j.get("tags") or [], desc=clean(j.get("description")), url=j.get("url"), posted=iso(j.get("created_at")), source="Arbeitnow")


def remoteok():
    data = get("https://remoteok.com/api")          # full recent feed; filtered for QA below
    data = [d for d in data if isinstance(d, dict) and d.get("position")] if isinstance(data, list) else []
    print(f"remoteok raw: {len(data)}")
    for j in data:
        sal = f"USD {j['salary_min']:,}–{j['salary_max']:,} / year" if j.get("salary_min") else ""
        yield dict(id="ro" + str(j.get("id")), title=j.get("position"), company=j.get("company"), logo=j.get("company_logo") or j.get("logo") or "",
                   loc=j.get("location") or "Remote", remote=True, salary=sal, type="", tags=j.get("tags") or [],
                   desc=clean(j.get("description")), url=j.get("apply_url") or j.get("url"), posted=iso(j.get("date")), source="Remote OK")


def workingnomads():
    data = get("https://www.workingnomads.com/api/exposed_jobs/")
    data = data if isinstance(data, list) else []
    print(f"workingnomads raw: {len(data)}")
    for j in data:
        yield dict(id="wn" + re.sub(r"\W", "", str(j.get("url", "")))[-40:], title=clean(j.get("title"), 160), company=j.get("company_name"), logo="",
                   loc=j.get("location") or "Remote", remote=True, salary="", type="", tags=(j.get("tags") or "").split(",") if isinstance(j.get("tags"), str) else [],
                   desc=clean(j.get("description")), url=j.get("url"), posted=iso(j.get("pub_date")), source="Working Nomads")


def weworkremotely():
    import xml.etree.ElementTree as ET
    req = urllib.request.Request("https://weworkremotely.com/remote-jobs.rss", headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as r:
        data = r.read(10_000_001)
    items = list(ET.fromstring(data).iter("item"))
    print(f"weworkremotely raw: {len(items)}")
    import email.utils
    for it in items:
        t = clean(it.findtext("title"), 200)
        company, _, title = t.partition(": ")
        try:
            posted = email.utils.parsedate_to_datetime(it.findtext("pubDate")).isoformat()
        except Exception:
            posted = iso(None)
        yield dict(id="wwr" + re.sub(r"\W", "", it.findtext("link") or "")[-40:], title=title or t, company=company if title else "",
                   logo="", loc=clean(it.findtext("region") or "Remote", 80), remote=True, salary="", type="", tags=[],
                   desc=clean(it.findtext("description")), url=(it.findtext("link") or "").strip(), posted=posted, source="We Work Remotely")


def himalayas():
    raw = []
    for q in ("qa", "quality assurance", "test automation", "sdet"):
        try:
            d = get("https://himalayas.app/jobs/api/search?q=" + urllib.parse.quote(q) + "&limit=50")
            raw += d.get("jobs", []) if isinstance(d, dict) else []
        except Exception as e:
            print(f"himalayas '{q}' failed: {e}", file=sys.stderr)
    print(f"himalayas raw: {len(raw)}")
    for j in raw:
        locs = j.get("locationRestrictions") or []
        loc = ", ".join(l if isinstance(l, str) else str(l.get("name", "")) for l in locs) or "Remote"
        sal = ""
        if j.get("minSalary"):
            sal = f"{j.get('currency', 'USD')} {int(j['minSalary']):,}–{int(j.get('maxSalary') or j['minSalary']):,} / year"
        yield dict(id="hm" + re.sub(r"\W", "", str(j.get("guid") or j.get("applicationLink") or j.get("title")))[-40:],
                   title=j.get("title"), company=j.get("companyName"), logo="", loc=loc, remote=True, salary=sal,
                   type=j.get("employmentType") or "", tags=j.get("categories") or [], desc=clean(j.get("description") or j.get("excerpt")),
                   url=j.get("applicationLink") or j.get("guid"), posted=iso(j.get("pubDate")), source="Himalayas")


def jooble():
    """UAE office jobs. Needs a free API key from jooble.org/api/about saved as the JOOBLE_API_KEY secret."""
    key = os.environ.get("JOOBLE_API_KEY", "").strip()
    if not key:
        print("jooble: skipped (add the free JOOBLE_API_KEY secret for UAE office jobs)")
        return
    raw = []
    for kw in ("QA engineer", "test automation engineer", "quality assurance", "SDET", "software tester", "QA lead"):
        for loc in ("Dubai", "Abu Dhabi", "Sharjah"):
            try:
                body = json.dumps({"keywords": kw, "location": loc, "page": "1"}).encode()
                req = urllib.request.Request("https://jooble.org/api/" + urllib.parse.quote(key), data=body,
                                             headers={"Content-Type": "application/json", "User-Agent": UA})
                with urllib.request.urlopen(req, timeout=30) as r:
                    raw += json.loads(r.read(5_000_001)).get("jobs", [])
            except Exception as e:
                print(f"jooble '{kw}' {loc} failed: {type(e).__name__}", file=sys.stderr)
    print(f"jooble raw: {len(raw)}")
    for j in raw:
        yield dict(id="jb" + str(j.get("id")), title=clean(j.get("title"), 160), company=j.get("company") or "", logo="",
                   loc=j.get("location") or "UAE", remote=False, salary=j.get("salary") or "", type=j.get("type") or "",
                   tags=[], desc=clean(j.get("snippet")), url=j.get("link"), posted=iso(j.get("updated")), source="Jooble")


def main():
    now = dt.datetime.now(dt.timezone.utc)
    cutoff = now - dt.timedelta(days=30)
    jobs, seen = [], set()
    for src in (jooble, remotive, himalayas, jobicy, arbeitnow, remoteok, workingnomads, weworkremotely):
        n, dropped = 0, {"not QA": 0, "not software": 0, "too old": 0, "duplicate": 0}
        try:
            for j in src():
                if not j.get("title") or not re.match(r"^https?://\S+$", str(j.get("url") or "")):
                    continue
                j["title"] = clean(str(j["title"]), 160)
                j["company"] = clean(str(j.get("company") or ""), 80)
                j["loc"] = clean(str(j.get("loc") or ""), 80)
                j["salary"] = clean(str(j.get("salary") or ""), 60)
                j["type"] = clean(str(j.get("type") or ""), 40)
                j["url"] = str(j["url"])[:1000]
                j["logo"] = ""   # remote logos are not loaded (privacy, CSP)
                if not QA.search(j["title"]):
                    dropped["not QA"] += 1
                    continue
                if NOT_SOFTWARE.search(j["title"]):
                    dropped["not software"] += 1
                    continue
                if dt.datetime.fromisoformat(j["posted"]) < cutoff:
                    dropped["too old"] += 1
                    continue
                key = re.sub(r"\W", "", (j["title"] + (j["company"] or "")).lower())
                if key in seen:
                    dropped["duplicate"] += 1
                    continue
                seen.add(key)
                j["tags"] = [str(t) for t in j.get("tags", []) if t][:12]
                jobs.append(j)
                n += 1
        except Exception as e:
            print(f"{src.__name__} failed: {e}", file=sys.stderr)
        print(f"{src.__name__}: kept {n} | dropped {dropped}")
    jobs.sort(key=lambda j: j["posted"], reverse=True)
    jobs = jobs[:250]
    if not jobs:
        print("No jobs fetched; keeping previous file", file=sys.stderr)
        return
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump({"generated": now.isoformat(), "jobs": jobs}, f, ensure_ascii=False)
    print(f"Wrote {len(jobs)} jobs")


if __name__ == "__main__":
    main()
