# Pulse

An Android news app that shows **one story at a time**, learns from every swipe, and never shows the same story twice. This repo holds the Android app (`android/`), the API it talks to (`pulse/`, `api/`) and the news ingest worker (`worker/`).

- **No repeats.** Ten outlets covering one event become one card, with every outlet one tap away. Big events (budget day, elections) become a single *thread* card.
- **Interests that keep up.** Swipes, taps, reading time and pauses all nudge a set of interest clusters, which fade over about a month unless you keep engaging.
- **You stay in control.** Follow, mute or always show any topic, keyword, place or outlet. A slider per location tier, a trust slider per source, and a discovery slider for how much exploration you want.
- **A feed that ends.** When the good stories run out you get "You're all caught up", not filler.
- **Accounts.** Anyone can create an account; each one gets its own feed state, history, learned interests, rules, city and source trust. The news itself is shared.
- **Liquid glass UI.** Native Compose recreations of the components in [Glass-Morphism](https://github.com/Sasivisvan/Glass-Morphism) (segmented control, tab bar, toolbar, buttons), with a Full / Light / Off setting.

| Gesture | Meaning |
|---|---|
| Swipe right / → | More like this (saved to Liked) |
| Swipe left / ← | Seen, move on (neutral) |
| Swipe up / ↑ | Fine: it's okay, keep this kind of story steady |
| Swipe down / ↓ | Less like this |
| Tap / Enter | Story detail: every outlet, timeline, reader view |
| Double-tap | Save for later, no ranking change |
| Share / `s` | System share sheet (also counts as interest) |
| Undo / `u` | Take back any of the last five swipes |

## Android app

`android/` is a native Android app (Kotlin + Jetpack Compose), not a web wrapper: native swipe stack with haptics,
story detail, reader view, Seen, Liked, Controls and Insights, an offline copy of your last feed, an optional
daily "Morning brief" notification, and in-app updates.

- Download `Pulse.apk` from this repo's [Releases](../../releases), install it, and sign in or create an account. Builds made with repo variable `PULSE_DEFAULT_SERVER` need no server address; others ask for one.
- Each push that touches `android/` builds a signed APK (`.github/workflows/android.yml`) and publishes a release
  tagged `android-<versionCode>`. Installed apps see it and offer **Update** without leaving the app.
- To publish your own builds: set repo variable `ANDROID_RELEASE=true` and `ANDROID_PACKAGE`, and secrets
  `ANDROID_KEYSTORE_B64` / `ANDROID_KEYSTORE_PASSWORD` (a keystore with alias `pulse`).

## How it works

```
RSS feeds ──▶ ingest worker ──▶ MongoDB ──▶ API + ranker ──▶ Android app
(Google News,  (poll, dedupe,     (stories,    (score, variety,   (swipe, Seen,
 outlets,       embed, cluster,    profile)     exploration)       Liked, Controls)
 city feeds)    extract)              ▲                                 │
     ▲                                └──────── swipes update ──────────┘
     └── strong interests and follows spawn new Google News search feeds
```

- **Ingest** (`worker/ingest.py`): polls feeds with ETag/If-Modified-Since, resolves Google News links to the publisher, extracts article text and the best image, embeds headlines with a multilingual MiniLM model (Hindi and English land in the same story), and clusters articles into stories (cosine similarity plus shared key names, 72-hour window). It also flags *Update* cards for stories you've seen and groups big events into threads.
- **Ranking** (`pulse/ranker.py`): `score = 0.40·interest + 0.20·rules + 0.15·importance + 0.15·freshness + 0.10·source trust − penalties`. Then: no more than two cards in a row from one topic, ~10% exploration slots labelled *Something new*, and a guaranteed share of local stories.
- **Learning** (`pulse/profile.py`): an append-only interaction log is the source of truth. Interest clusters can be rebuilt from it at any time (Controls → Insights → Rebuild).

## Deploy (free, no server to maintain)

Pulse runs entirely on free tiers:

| Piece | Where | Why |
|---|---|---|
| API | **Vercel** (Python serverless function) | Auto-deploys on every push |
| Database | **MongoDB Atlas** M0 (free forever) | No expiry, 512 MB is plenty after pruning |
| News ingest | **GitHub Actions** cron | Hourly from 06:00 to 23:00 IST, plus twice overnight; the embedding model runs there for free |

1. **Database.** Create a free Atlas cluster and a database user. Allow access from `0.0.0.0/0` (Vercel and GitHub use dynamic IPs; the long random password is the protection). Copy the `mongodb+srv://` URI.
2. **Fork or copy this repo** (a private copy is recommended for your own deployment).
3. **GitHub Actions** (repo → Settings → Secrets and variables → Actions):
   - secret `MONGODB_URI`
   - variable `PULSE_DEPLOY` = `true` (turns on the ingest schedule; forks stay off by default)
   - run the **Ingest news** workflow once by hand to fetch your first stories
4. **Vercel**: import the repo. Framework preset "Other"; `vercel.json` does the rest. Add the environment variables `MONGODB_URI` and `PULSE_SECRET` (see `.env.example`), and optionally `PULSE_APP_REPO` (`owner/repo` whose releases carry the APK) and `PULSE_MAX_USERS`.
5. Set repo variable `PULSE_DEFAULT_SERVER` (your Vercel URL) where the APK is built, so the app needs no server address. Opening the server URL in a browser redirects to the latest APK.

Sign-ups are rate limited (`PULSE_SIGNUPS_PER_HOUR`, default 30) and capped (`PULSE_MAX_USERS`, default 1000). The API refuses to start when hosted without `PULSE_SECRET`.

Upgrading from the single-user version: run `OWNER_USERNAME=you PULSE_PASSWORD=old-password python -m worker.migrate_users` once; it creates your account and moves your history onto it.

### Self-host instead

```sh
cp .env.example .env   # set PULSE_SECRET
docker compose up -d   # API on :8000, Mongo and the ingest loop included
```

## Local development

```sh
uv venv --python 3.12 && uv pip install -r requirements.txt -r worker/requirements.txt uvicorn
cp .env.example .env    # point MONGODB_URI at Atlas or a local mongod
set -a; . ./.env; set +a
python -m worker.ingest             # fetch news once
uvicorn pulse.app:app --reload      # API on :8000 (point a debug build at http://10.0.2.2:8000)
```

## Notes

- **Personal use only.** Pulse shows headlines, short snippets and links, and keeps extracted text only for your own reader view. Respect publishers' terms.
- Google News RSS feeds are undocumented and can change; outlet feeds are the backbone.
- Default feeds are India-focused (Google News India edition, The Hindu, Indian Express, NDTV, Hindustan Times, Mint, Times of India, BBC World, Hacker News). Add any RSS feed in Controls.

## License

MIT
