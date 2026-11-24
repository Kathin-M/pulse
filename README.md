# Pulse

A private, self-hosted news app that shows **one story at a time**, learns from every swipe, and never shows the same story twice.

- **No repeats.** Ten outlets covering one event become one card, with every outlet one tap away. Big events (budget day, elections) become a single *thread* card.
- **Interests that keep up.** Swipes, taps, reading time and pauses all nudge a set of interest clusters, which fade over about a month unless you keep engaging.
- **You stay in control.** Follow, mute or always show any topic, keyword, place or outlet. A slider per location tier, a trust slider per source, and a discovery slider for how much exploration you want.
- **A feed that ends.** When the good stories run out you get "You're all caught up", not filler.

| Gesture | Meaning |
|---|---|
| Swipe right / → | More like this (saved to Liked) |
| Swipe left / ← | Seen, move on (neutral) |
| Swipe down / ↓ | Less like this |
| Tap / Enter | Story detail: every outlet, timeline, reader view |
| Double-tap | Save for later, no ranking change |
| Undo / `u` | Take back any of the last five swipes |

## How it works

```
RSS feeds ──▶ ingest worker ──▶ MongoDB ──▶ API + ranker ──▶ PWA
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
| PWA + API | **Vercel** (Python serverless function) | Auto-deploys on every push |
| Database | **MongoDB Atlas** M0 (free forever) | No expiry, 512 MB is plenty after pruning |
| News ingest | **GitHub Actions** cron | Hourly from 06:00 to 23:00 IST, plus twice overnight; the embedding model runs there for free |

1. **Database.** Create a free Atlas cluster and a database user. Allow access from `0.0.0.0/0` (Vercel and GitHub use dynamic IPs; the long random password is the protection). Copy the `mongodb+srv://` URI.
2. **Fork or copy this repo** (a private copy is recommended for your own deployment).
3. **GitHub Actions** (repo → Settings → Secrets and variables → Actions):
   - secret `MONGODB_URI`
   - variable `PULSE_DEPLOY` = `true` (turns on the ingest schedule; forks stay off by default)
   - run the **Ingest news** workflow once by hand to fetch your first stories
4. **Vercel**: import the repo. Framework preset "Other"; `vercel.json` does the rest. Add the environment variables `MONGODB_URI`, `PULSE_PASSWORD` and `PULSE_SECRET` (see `.env.example`).
5. Open the site on your phone and **Add to Home Screen**. The app updates itself after every deploy.

The API refuses to run when hosted without `PULSE_PASSWORD`, so a deployment is never left open by accident.

### Self-host instead

```sh
cp .env.example .env   # set PULSE_PASSWORD and PULSE_SECRET
docker compose up -d   # app on :8000, Mongo and the ingest loop included
```

## Local development

```sh
uv venv --python 3.12 && uv pip install -r requirements.txt -r worker/requirements.txt uvicorn
cp .env.example .env    # point MONGODB_URI at Atlas or a local mongod; leave PULSE_PASSWORD empty for no login
set -a; . ./.env; set +a
python -m worker.ingest             # fetch news once
uvicorn pulse.app:app --reload      # API on :8000
cd web && npm install && npm run dev  # PWA on :5173, proxies /api
```

## Notes

- **Personal use only.** Pulse shows headlines, short snippets and links, and keeps extracted text only for your own reader view. Respect publishers' terms.
- Google News RSS feeds are undocumented and can change; outlet feeds are the backbone.
- Default feeds are India-focused (Google News India edition, The Hindu, Indian Express, NDTV, Hindustan Times, Mint, Times of India, BBC World, Hacker News). Add any RSS feed in Controls.

## License

MIT
