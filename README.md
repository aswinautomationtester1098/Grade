# Grade

A free, private app for **jobs and learning**. It works as an installable web app (PWA) and as an Android APK built automatically by GitHub Actions.

**Install:** open https://aswinautomationtester1098.github.io/Grade/ in Chrome and choose "Add to Home screen", or download the APK from the Releases page (`latest`).

## What it does
- **Jobs** – daily job openings for your career field (QA, Software, Data & AI, Product, Design, Marketing), matched to your resume on your phone. Nothing is uploaded.
- **Company check** – before you apply: public summary, recent headlines, risk flags, links to review sites and a short checklist.
- **Applied** – tracker for every application, with stages and insights.
- **Learn** – Coding Padikkam (Python, JavaScript, Java, SQL: quick cards, lessons, quizzes, mini projects, in Malayalam-friendly style), Interview quick reference (top questions by topic), Experience Grade (career advice plus fresh industry reads), and a daily news briefing for your field and country.
- **Home** – daily plan, streak and XP, best matches, skills to learn from the jobs you see.
- Light, dark or automatic theme. Works offline for learning content.

## How it is built
- `web/` – the whole app (`index.html` plus data files). No server, no account.
- `scripts/fetch_news.py`, `fetch_jobs.py`, `fetch_companies.py` – run daily by `.github/workflows/site.yml`; results are published to Pages and saved on the `data` branch.
- `android/` – a thin WebView host; `.github/workflows/android.yml` builds the signed APK.
- Secrets (optional): `JOOBLE_API_KEY`, `ANTHROPIC_API_KEY`, and the Android signing key. Never commit them.

## Privacy
Your resume, applications and progress stay on the device. Company facts come from Wikipedia (CC BY-SA) and Google News headlines; review sites are only linked, never copied.
