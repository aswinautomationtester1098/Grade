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
import urllib.request

OUT = os.path.join(os.path.dirname(__file__), "..", "web", "jobs.json")
UA = "Mozilla/5.0 (GradeJobs/1.0; personal job alerts)"
QA = re.compile(r"\b(qa|q\.a\.|quality (assurance|engineer)|test(ing|er)?\b|sdet|automation engineer|test automation|quality engineering)", re.I)
TAG = re.compile(r"<[^>]+>")


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
    for j in get("https://remotive.com/api/remote-jobs?category=qa&limit=100").get("jobs", []):
        yield dict(id="rm" + str(j.get("id")), title=j.get("title"), company=j.get("company_name"), logo=j.get("company_logo"),
                   loc=j.get("candidate_required_location") or "Remote", remote=True, salary=j.get("salary") or "",
                   type=(j.get("job_type") or "").replace("_", " "), tags=j.get("tags") or [], desc=clean(j.get("description")),
                   url=j.get("url"), posted=iso(j.get("publication_date")), source="Remotive")


def jobicy():
    for j in get("https://jobicy.com/api/v2/remote-jobs?count=50&tag=qa").get("jobs", []):
        sal = ""
        if j.get("annualSalaryMin"):
            sal = f"{j.get('salaryCurrency', '')} {j['annualSalaryMin']:,}–{j.get('annualSalaryMax', j['annualSalaryMin']):,} / year".strip()
        yield dict(id="jc" + str(j.get("id")), title=clean(j.get("jobTitle")), company=j.get("companyName"), logo=j.get("companyLogo"),
                   loc=j.get("jobGeo") or "Remote", remote=True, salary=sal, type=", ".join(j.get("jobType") or []) if isinstance(j.get("jobType"), list) else (j.get("jobType") or ""),
                   tags=[j.get("jobLevel") or ""], desc=clean(j.get("jobDescription") or j.get("jobExcerpt")),
                   url=j.get("url"), posted=iso(j.get("pubDate")), source="Jobicy")


def arbeitnow():
    for j in get("https://www.arbeitnow.com/api/job-board-api").get("data", []):
        if not QA.search(j.get("title", "")):
            continue
        yield dict(id="an" + str(j.get("slug")), title=j.get("title"), company=j.get("company_name"), logo="",
                   loc=j.get("location") or "", remote=bool(j.get("remote")), salary="", type=", ".join(j.get("job_types") or []),
                   tags=j.get("tags") or [], desc=clean(j.get("description")), url=j.get("url"), posted=iso(j.get("created_at")), source="Arbeitnow")


def remoteok():
    data = get("https://remoteok.com/api?tag=qa")
    for j in data[1:] if isinstance(data, list) else []:
        sal = f"USD {j['salary_min']:,}–{j['salary_max']:,} / year" if j.get("salary_min") else ""
        yield dict(id="ro" + str(j.get("id")), title=j.get("position"), company=j.get("company"), logo=j.get("company_logo") or j.get("logo") or "",
                   loc=j.get("location") or "Remote", remote=True, salary=sal, type="", tags=j.get("tags") or [],
                   desc=clean(j.get("description")), url=j.get("apply_url") or j.get("url"), posted=iso(j.get("date")), source="Remote OK")


def main():
    now = dt.datetime.now(dt.timezone.utc)
    cutoff = now - dt.timedelta(days=21)
    jobs, seen = [], set()
    for src in (remotive, jobicy, arbeitnow, remoteok):
        n = 0
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
                if not QA.search(j["title"] + " " + " ".join(map(str, j.get("tags", [])))):
                    continue
                if dt.datetime.fromisoformat(j["posted"]) < cutoff:
                    continue
                key = re.sub(r"\W", "", (j["title"] + (j["company"] or "")).lower())
                if key in seen:
                    continue
                seen.add(key)
                j["tags"] = [str(t) for t in j.get("tags", []) if t][:12]
                jobs.append(j)
                n += 1
        except Exception as e:
            print(f"{src.__name__} failed: {e}", file=sys.stderr)
        print(f"{src.__name__}: {n} QA jobs")
    jobs.sort(key=lambda j: j["posted"], reverse=True)
    jobs = jobs[:150]
    if not jobs:
        print("No jobs fetched; keeping previous file", file=sys.stderr)
        return
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump({"generated": now.isoformat(), "jobs": jobs}, f, ensure_ascii=False)
    print(f"Wrote {len(jobs)} jobs")


if __name__ == "__main__":
    main()
