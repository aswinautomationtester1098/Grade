# Grade

Aswin's personal coach: walking, water, multivitamin, habits, daily expenses, a daily QA/tech/UAE/India briefing, and QA jobs matched to your resume.

- **Online web app** on GitHub Pages: works on any phone and keeps working offline after the first visit.
- **Android app (APK)**: always-on step counting using the phone's step-counter chip, plus background reminders.
- **Daily refresh** at 05:30 Dubai time: a new briefing and new job openings. Yesterday's briefing disappears automatically.

## Go live (one time, about 5 minutes)

1. Create a GitHub account at github.com, if you don't have one.
2. Create a new **public** repository named `grade`.
3. Upload everything in this folder to the repository, keeping the folder structure (`.github`, `android`, `scripts`, `web`).
   - The easiest way is to connect GitHub to Claude (claude.ai → Settings → Connectors). Claude can then push the code for you.
4. In the repository, open **Settings → Pages**, and under **Source** choose **GitHub Actions**.
5. Open the **Actions** tab and run **Publish Grade** once. It also runs automatically every morning.

Your app is then live at `https://YOUR-USERNAME.github.io/grade/`. On your phone, open it in Chrome and choose **Add to Home screen**.

## Before the first APK: add your private signing key (recommended)

Open the file **Grade-private-signing-key.txt** (sent separately, never put it in the repository). In GitHub, open **Settings → Secrets and variables → Actions** and add the two secrets it lists. From then on, APKs are signed with a key only you control. See SECURITY.md.

## Install the Android app

After the **Build Grade APK** action finishes, go to the repository's **Releases**, open **Grade (latest build)**, download **Grade.apk** on your phone and open it. Allow "install unknown apps" for your browser when Android asks.

On first launch, allow **Physical activity** and **Notifications**. Then, in Settings → Always-on tracking, tap **Allow background running** so your phone doesn't stop the step counter.

Every time the code changes, a new APK is built. It installs over the old one and keeps your data.

## Optional: AI summaries for the briefing

In the repository, open **Settings → Secrets and variables → Actions → New repository secret**, name it `ANTHROPIC_API_KEY`, and paste your Anthropic API key. Each news item then gets a one-line summary and a "why it matters" line. This is a paid API with a small daily cost. Without the key, headlines and sources are shown.

GitHub pauses scheduled jobs in repositories with no activity for 60 days. If the briefing stops updating, open **Actions** and re-enable **Publish Grade**.

## Privacy

Steps, water, habits, expenses and your resume stay on your phone (browser storage or the app's storage). Resume matching runs on the phone. Use **Settings → Back up** to save a copy.

## Folder guide

| Folder | What it is |
| --- | --- |
| `web/` | The app itself (HTML, offline cache, artwork) |
| `scripts/fetch_news.py` | Builds the daily briefing (`news.json`) |
| `scripts/fetch_jobs.py` | Collects QA openings (`jobs.json`) from Remotive, Jobicy, Arbeitnow and Remote OK |
| `android/` | Native Android app: WebView, step-counter service, reminders, boot restart |
| `.github/workflows/` | Daily publish to GitHub Pages and the automatic APK build |

## Before a Play Store release

- Replace `android/keystore/grade.jks` with your own private upload key, and use Play App Signing.
- Switch the briefing to a licensed news API. The Google News RSS feeds are for personal use.
- Add a privacy policy, and complete the Health apps and foreground-service declarations in Play Console.
- New personal developer accounts must run a closed test with 12 testers for 14 days.

General wellness guidance only, not medical advice.
