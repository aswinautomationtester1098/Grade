# Grade security review (24 Nov 2026)

Tested with automated attack scripts (malicious job and news feeds, tampered backup files, framing) plus a code review of the web app, the Android app and the build automation.

## Fixed

| # | Severity | Issue | Fix |
|---|---|---|---|
| 1 | High | A job feed could inject a `javascript:` apply link (runs code on tap) | Only `http(s)` links are allowed, both in the fetch scripts and in the app |
| 2 | High | A tampered backup file could run code (stored XSS via the expense id) | Every restored value is validated by type, range and length; all output is escaped |
| 3 | Medium | No Content Security Policy | Strict CSP: scripts only from the app and cdnjs, no frames, objects or form posts |
| 4 | Medium | PDF reader pdf.js 3.11.174 affected by CVE-2024-4367 | `isEvalSupported:false` (official mitigation); CSP also blocks eval |
| 5 | Medium | Outdated Word reader (mammoth 1.6.0) | Upgraded to 1.13.0 |
| 6 | Medium | Android JavaScript bridge usable by any loaded page | Every bridge method checks that the current page is Grade's own; inputs are size-checked |
| 7 | Medium | Android WebView opened any link type, including `intent:` | Only http(s) links open, in the browser; everything else is blocked |
| 8 | Low | WebView file and content access enabled | Disabled; mixed content blocked; cleartext traffic off |
| 9 | Low | App data included in cloud/adb backup | `allowBackup=false` (use the in-app Back up button) |
| 10 | Low | Page could be embedded by other sites (clickjacking) | Frame-busting: a framed copy is blanked |
| 11 | Low | GitHub Actions referenced by movable tags | Pinned to exact commit hashes |
| 12 | Low | Shared APK signing key in the public repo | Private key support via repository secrets (key provided separately) |
| 13 | Low | Unlimited feed and file sizes | Feed and API responses capped; resume ≤ 8 MB, photo ≤ 25 MB, backup ≤ 20 MB |

## Remaining risks (accepted)

- The CSP allows inline script, because the app is a single file. All dynamic text is escaped, which the attack tests confirmed.
- CDN scripts have no integrity hashes. They are limited to cdnjs and load only when you upload a resume.
- Data on the phone is not separately encrypted. It relies on Android's device encryption and the browser's storage. Backup files in Downloads/Grade can be read by apps with storage access, so keep them private.
- Until you add the private signing key secrets, APKs are signed with the shared key in the repo.
