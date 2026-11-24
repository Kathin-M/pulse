"""Pulse ingest worker.

Runs on a schedule (GitHub Actions cron, or the docker-compose worker loop):
  1. sync generated feeds (city, followed topics/keywords, learned interests)
  2. poll every enabled feed with ETag / If-Modified-Since
  3. drop exact duplicates, enrich (language, topics, places, names), embed
  4. cluster articles into stories and flag "update" cards
  5. fetch article pages: resolve Google News links, extract text, best image
  6. group big events into threads
  7. prune old data

Usage:  python -m worker.ingest            (needs MONGODB_URI)
"""
from __future__ import annotations

import base64
import io
import logging
import os
import re
import sys
import time
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timedelta, timezone
from email.utils import parsedate_to_datetime

import feedparser
import httpx
import numpy as np
from pymongo import DeleteOne, UpdateOne

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from pulse import db as D  # noqa: E402
from pulse import taxonomy as T  # noqa: E402
from pulse.sources import city_sources, default_sources, google_search_url  # noqa: E402

log = logging.getLogger("pulse.ingest")

UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"
MODEL_NAME = "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"
WINDOW_H = 72
MAX_FETCH = int(os.environ.get("PULSE_MAX_FETCH", "350"))
TIME_BUDGET_S = int(os.environ.get("PULSE_TIME_BUDGET", "420"))

# Clustering thresholds (cosine similarity of multilingual MiniLM embeddings)
SIM_WITH_NAMES = 0.80     # same event if this close AND shares a key name
SIM_ALONE = 0.87          # same event even without a shared name
SIM_ATTACH = 0.66         # opinion / live blogs attach to a story at this level (+ shared name)
SIM_UPDATE_NOVEL = 0.78   # new article this different from what you saw -> update card
SIM_THREAD = 0.60

_model = None
_started = time.monotonic()


def elapsed() -> float:
    return time.monotonic() - _started


def embedder():
    global _model
    if _model is None:
        from fastembed import TextEmbedding
        _model = TextEmbedding(MODEL_NAME, cache_dir=os.environ.get("FASTEMBED_CACHE", ".cache/fastembed"))
    return _model


def embed(texts: list[str]) -> np.ndarray:
    if not texts:
        return np.zeros((0, D.DIM), dtype=np.float32)
    v = np.array(list(embedder().embed(texts, batch_size=64)), dtype=np.float32)
    v /= np.linalg.norm(v, axis=1, keepdims=True) + 1e-9
    return v


# ------------------------------------------------------------------ feeds sync
def upsert_source(db, s: dict) -> None:
    doc = {"name": s["name"], "kind": s.get("kind", "outlet"), "tier": s.get("tier"),
           "topic_hint": s.get("topic_hint"), "query": s.get("query")}
    for k in ("rule_id", "cluster_id"):
        if k in s:
            doc[k] = s[k]
    db.sources.update_one(
        {"url": s["url"]},
        {"$set": doc, "$setOnInsert": {"_id": D.next_id(db, "sources"), "trust": s.get("trust", 0.6),
                                       "learned_bias": 0.0, "enabled": True, "created_at": D.now()}},
        upsert=True,
    )


def sync_sources(db) -> None:
    prefs = D.get_prefs(db)
    if db.sources.count_documents({"kind": {"$in": ["outlet", "google"]}}) == 0:
        for s in default_sources():
            upsert_source(db, s)

    # City / state feeds follow the current preference.
    wanted = city_sources(prefs["city"], prefs["state"])
    wanted_urls = {s["url"] for s in wanted}
    for s in wanted:
        upsert_source(db, s)
    db.sources.delete_many({"kind": "city", "url": {"$nin": list(wanted_urls)}})

    # Followed keywords / places / niche topics become Google News search feeds.
    broad = set(T.SECTION_TOPICS.values())
    rule_urls = set()
    for r in db.rules.find({"mode": {"$in": ["follow", "always"]}, "kind": {"$in": ["keyword", "place", "topic"]}}):
        if r["kind"] == "topic" and r["label"] in broad:
            continue
        q = f'"{r["label"]}"' if r["kind"] != "topic" else r["label"]
        url = google_search_url(q)
        rule_urls.add(url)
        upsert_source(db, {"url": url, "name": f"Search · {r['label']}", "kind": "google_search",
                           "tier": None, "query": q, "rule_id": r["_id"],
                           "topic_hint": r["label"] if r["kind"] == "topic" else None})
    db.sources.delete_many({"kind": "google_search", "url": {"$nin": list(rule_urls)}})

    # Strong learned interests spawn their own search feeds (max 8).
    auto_urls = set()
    clusters = list(db.clusters.find({"polarity": 1}))
    clusters.sort(key=lambda c: -effective_weight(c))
    for c in clusters[:8]:
        if effective_weight(c) < 2.5 or c.get("signals", 0) < 4:
            continue
        terms = [t for t, _ in Counter(c.get("terms") or {}).most_common(6)
                 if T.place_kind(t) is None and len(t) > 2][:2]
        if not terms:
            continue
        q = " ".join(f'"{t}"' for t in terms)
        url = google_search_url(q)
        auto_urls.add(url)
        upsert_source(db, {"url": url, "name": f"Learned · {' + '.join(terms)}", "kind": "auto_interest",
                           "tier": None, "query": q, "cluster_id": c["_id"]})
    db.sources.delete_many({"kind": "auto_interest", "url": {"$nin": list(auto_urls)}})


def effective_weight(c: dict) -> float:
    age_days = (D.now() - D.aware(c["updated_at"])).total_seconds() / 86400
    return c["weight"] * 0.5 ** (age_days / 30)


# ------------------------------------------------------------------ polling
def parse_date(entry) -> datetime | None:
    for key in ("published_parsed", "updated_parsed"):
        st = entry.get(key)
        if st:
            return datetime(*st[:6], tzinfo=timezone.utc)
    for key in ("published", "updated"):
        if entry.get(key):
            try:
                return parsedate_to_datetime(entry[key]).astimezone(timezone.utc)
            except Exception:
                pass
    return None


def entry_image(entry) -> str | None:
    for key in ("media_content", "media_thumbnail"):
        items = entry.get(key) or []
        best = None
        for it in items:
            url = it.get("url")
            if not url:
                continue
            w = int(it.get("width") or 0)
            if best is None or w > best[0]:
                best = (w, url)
        if best:
            return best[1]
    for enc in entry.get("enclosures") or []:
        if (enc.get("type") or "").startswith("image") and enc.get("href"):
            return enc["href"]
    html = entry.get("summary", "") + "".join(c.get("value", "") for c in entry.get("content", []) or [])
    m = re.search(r'<img[^>]+src="([^"]+)"', html)
    return m.group(1) if m else None


def poll_source(client: httpx.Client, src: dict) -> tuple[dict, list[dict], dict]:
    headers = {}
    if src.get("etag"):
        headers["If-None-Match"] = src["etag"]
    if src.get("modified"):
        headers["If-Modified-Since"] = src["modified"]
    upd = {"last_polled": D.now()}
    try:
        r = client.get(src["url"], headers=headers)
    except Exception as e:  # network error
        upd["last_error"] = str(e)[:200]
        return src, [], upd
    if r.status_code == 304:
        upd["last_error"] = None
        return src, [], upd
    if r.status_code >= 400:
        upd["last_error"] = f"HTTP {r.status_code}"
        return src, [], upd
    upd["etag"] = r.headers.get("etag")
    upd["modified"] = r.headers.get("last-modified")
    upd["last_error"] = None
    feed = feedparser.parse(r.content)
    items = []
    cutoff = D.now() - timedelta(hours=WINDOW_H)
    is_google = "news.google.com" in src["url"]
    for e in feed.entries[:120]:
        link = e.get("link")
        title = T.clean_html(e.get("title", ""))
        if not link or not title:
            continue
        published = parse_date(e) or D.now()
        if published < cutoff:
            continue
        published = min(published, D.now())
        publisher = None
        summary = T.clean_html(e.get("summary", ""))
        if is_google:
            src_info = e.get("source") or {}
            publisher = src_info.get("title")
            if publisher and title.endswith(" - " + publisher):
                title = title[: -len(publisher) - 3]
            summary = ""  # Google's description just repeats the headline
        else:
            publisher = src["name"].split(" · ")[0]
            if summary.lower().startswith(title.lower()[:40]):
                summary = summary[len(title):].strip(" -:|")
        items.append({
            "url": link, "title": title, "summary": T.short_summary(summary, 60) if summary else "",
            "publisher": publisher or src["name"], "published_at": published,
            "image": None if is_google else entry_image(e), "is_google": is_google,
        })
    return src, items, upd


def poll_all(db) -> list[tuple[dict, dict]]:
    sources = list(db.sources.find({"enabled": True}))
    results = []
    with httpx.Client(headers={"User-Agent": UA}, timeout=15, follow_redirects=True) as client:
        with ThreadPoolExecutor(8) as pool:
            for fut in as_completed([pool.submit(poll_source, client, s) for s in sources]):
                src, items, upd = fut.result()
                if items:
                    upd["last_new_at"] = D.now()
                db.sources.update_one({"_id": src["_id"]}, {"$set": upd})
                results.extend((src, it) for it in items)
    log.info("polled %d feeds, %d recent items", len(sources), len(results))
    return results


# ------------------------------------------------------------------ insert + enrich
def insert_new(db, polled: list[tuple[dict, dict]]) -> list[dict]:
    prefs = D.get_prefs(db)
    since = D.now() - timedelta(hours=WINDOW_H + 24)
    seen_urls = set()
    seen_titles = {d["title_norm"] for d in db.articles.find({"published_at": {"$gte": since}}, {"title_norm": 1})}
    # Prefer outlet items over Google copies of the same headline: outlets first.
    polled.sort(key=lambda p: p[1]["is_google"])
    fresh = []
    for src, it in polled:
        tn = T.norm_title(it["title"])
        if it["url"] in seen_urls or tn in seen_titles or len(tn) < 12:
            continue
        seen_urls.add(it["url"])
        seen_titles.add(tn)
        fresh.append((src, it, tn))
    if not fresh:
        return []
    existing = {d["url"] for d in db.articles.find({"url": {"$in": [it["url"] for _, it, _ in fresh]}}, {"url": 1})}
    fresh = [f for f in fresh if f[1]["url"] not in existing]
    if not fresh:
        return []

    texts = [it["title"] + ". " + (it["summary"] or "")[:300] for _, it, _ in fresh]
    vecs = embed(texts)
    docs = []
    ids = D.next_ids(db, "articles", len(fresh))
    for (src, it, tn), v, new_id in zip(fresh, vecs, ids):
        text = it["title"] + ". " + (it["summary"] or "")
        places = T.find_places(text)
        doc = {
            "_id": new_id,
            "source_id": src["_id"], "url": it["url"], "title": it["title"], "title_norm": tn,
            "summary": it["summary"], "body": None, "image": it["image"], "image_w": None, "image_h": None,
            "image_lqip": None, "publisher": it["publisher"], "published_at": it["published_at"],
            "fetched_at": D.now(), "lang": T.detect_lang(it["title"]), "embedding": D.vec_to_bin(v),
            "story_id": None, "topics": T.tag_topics(text, src.get("topic_hint")), "places": places,
            "entities": T.entities(it["title"] + ". " + (it["summary"] or "")),
            "kind": T.article_kind(it["url"], it["title"]), "has_video": False,
            "needs_fetch": True, "source_tier": src.get("tier"),
        }
        doc["tier"] = T.story_tier(places, prefs, src.get("tier"))
        docs.append(doc)
    db.articles.insert_many(docs, ordered=False)
    log.info("inserted %d new articles", len(docs))
    return docs


# ------------------------------------------------------------------ clustering
def _keys(a: dict) -> set[str]:
    return T.key_tokens(a.get("entities") or [])


def _words(a: dict) -> set[str]:
    return T.content_words(a["title"] + " " + (a.get("summary") or "")[:200])


class Index:
    """In-memory view of recent articles for nearest-neighbour clustering."""

    def __init__(self, db, exclude_ids: set[int] = frozenset()):
        since = D.now() - timedelta(hours=WINDOW_H)
        rows = [r for r in db.articles.find(
            {"published_at": {"$gte": since}, "story_id": {"$ne": None}},
            {"embedding": 1, "story_id": 1, "entities": 1, "kind": 1, "title": 1, "summary": 1}) if r["_id"] not in exclude_ids]
        self.vecs = [D.bin_to_vec(r["embedding"]) for r in rows]
        self.story = [r["story_id"] for r in rows]
        self.keys = [_keys(r) for r in rows]
        self.words = [_words(r) for r in rows]
        self.df = Counter()
        self.wdf = Counter()
        for k, w in zip(self.keys, self.words):
            self.df.update(k)
            self.wdf.update(w)
        self.n_docs = max(len(rows), 1)
        self.no_merge = {s["_id"] for s in db.stories.find({"no_merge": True, "last_updated": {"$gte": since}}, {"_id": 1})}
        self._M = None

    def _specific(self, toks: set[str], df: Counter) -> set[str]:
        limit = max(5, 0.025 * self.n_docs)
        return {t for t in toks if df.get(t, 0) <= limit}

    def add(self, vec, story_id, keys, words):
        self.vecs.append(vec)
        self.story.append(story_id)
        self.keys.append(keys)
        self.words.append(words)
        self.df.update(keys)
        self.wdf.update(words)
        self.n_docs += 1
        self._M = None

    def best_story(self, vec, keys, words, kind) -> int | None:
        if not self.vecs:
            return None
        if self._M is None or self._M.shape[0] != len(self.vecs):
            self._M = np.vstack(self.vecs)
        sims = self._M @ vec
        order = np.argsort(-sims)[:30]
        my_keys = self._specific(keys, self.df)
        my_words = self._specific(words, self.wdf)
        for i in order:
            s = float(sims[i])
            if s < SIM_ATTACH:
                break
            sid = self.story[i]
            shared_keys = my_keys & self._specific(self.keys[i], self.df)
            shared_words = my_words & self._specific(self.words[i], self.wdf)
            if kind in ("opinion", "live"):
                if (s >= SIM_ATTACH and (shared_keys or len(shared_words) >= 3)) or s >= SIM_WITH_NAMES:
                    return sid
                continue
            if sid in self.no_merge:
                if s >= 0.94:
                    return sid
                continue
            if (s >= SIM_ALONE
                    or (s >= SIM_WITH_NAMES and shared_keys)
                    or (s >= 0.76 and len(shared_keys) >= 2)
                    or (s >= 0.72 and len(shared_keys) >= 2 and len(shared_words) >= 4)
                    or (s >= 0.80 and len(shared_words) >= 3)):
                return sid
        return None


def cluster(db, new_docs: list[dict], trust: dict) -> set[int]:
    if not new_docs:
        return set()
    idx = Index(db, exclude_ids={d["_id"] for d in new_docs})
    id_pool = list(reversed(D.next_ids(db, "stories", len(new_docs))))
    touched: set[int] = set()
    new_stories: dict[int, dict] = {}
    new_docs.sort(key=lambda d: d["published_at"])
    for d in new_docs:
        v = D.bin_to_vec(d["embedding"])
        keys, words = _keys(d), _words(d)
        sid = idx.best_story(v, keys, words, d["kind"])
        if sid is None:
            sid = id_pool.pop()
            new_stories[sid] = {"_id": sid, "thread_id": None, "pending_update_article_id": None, "no_merge": False}
        d["story_id"] = sid
        idx.add(v, sid, keys, words)
        touched.add(sid)
    if new_stories:
        db.stories.insert_many(list(new_stories.values()))
    db.articles.bulk_write([UpdateOne({"_id": d["_id"]}, {"$set": {"story_id": d["story_id"]}}) for d in new_docs], ordered=False)
    refresh_stories(db, touched, trust)
    flag_updates(db, new_docs)
    log.info("clustered %d articles into %d stories (%d new)", len(new_docs), len(touched), len(new_stories))
    return touched


def refresh_stories(db, sids: set[int], trust: dict) -> None:
    """Recompute centroid, lead, counts, topics for many stories with one read and one bulk write."""
    if not sids:
        return
    by_story: dict[int, list[dict]] = {}
    for a in db.articles.find({"story_id": {"$in": list(sids)}}, {"body": 0}):
        by_story.setdefault(a["story_id"], []).append(a)
    ops = []
    for sid in sids:
        arts = by_story.get(sid)
        if not arts:
            ops.append(DeleteOne({"_id": sid}))
            continue
        vecs = np.vstack([D.bin_to_vec(a["embedding"]) for a in arts])
        centroid = D.normalize(vecs.mean(axis=0))
        reports = [a for a in arts if a["kind"] in ("report", "video")] or arts
        topics = Counter(t for a in arts for t in a["topics"])
        places = Counter(p for a in arts for p in a["places"])
        ents = Counter(e for a in arts for e in a["entities"])
        tiers = Counter(a.get("tier") for a in arts)
        tier = "city" if tiers.get("city") else "state" if tiers.get("state") else tiers.most_common(1)[0][0]
        lead = max(reports, key=lambda a: (trust.get(a.get("source_id"), 0.6), (a.get("body_len") or 0) > 600,
                                           a.get("body_len") or len(a.get("summary") or "")))
        ops.append(UpdateOne({"_id": sid}, {"$set": {
            "centroid": D.vec_to_bin(centroid),
            "first_seen": min(D.aware(a["published_at"]) for a in arts),
            "last_updated": max(D.aware(a["published_at"]) for a in arts),
            "source_count": len({a["publisher"] for a in reports}),
            "topics": [t for t, _ in topics.most_common(3)],
            "places": [p for p, _ in places.most_common(4)],
            "entities": [e for e, _ in ents.most_common(12)],
            "tier": tier,
            "lead_article_id": lead["_id"],
        }}))
    for i in range(0, len(ops), 500):
        db.stories.bulk_write(ops[i:i + 500], ordered=False)


def flag_updates(db, new_docs: list[dict]) -> None:
    """A story you already saw gets an Update card when a clearly different report arrives."""
    by_story: dict[int, list[dict]] = {}
    for d in new_docs:
        if d["kind"] == "report":
            by_story.setdefault(d["story_id"], []).append(d)
    if not by_story:
        return
    states = {s["_id"]: s for s in db.state.find({"_id": {"$in": list(by_story)}, "status": {"$in": ["seen", "liked"]}})}
    if not states:
        return
    seen_ids = {i for st in states.values() for i in (st.get("seen_article_ids") or [])}
    seen_arts = {a["_id"]: a for a in db.articles.find({"_id": {"$in": list(seen_ids)}}, {"embedding": 1, "entities": 1})}
    for sid, st in states.items():
        seen = [seen_arts[i] for i in (st.get("seen_article_ids") or []) if i in seen_arts]
        if not seen:
            continue
        M = np.vstack([D.bin_to_vec(a["embedding"]) for a in seen])
        seen_keys = T.key_tokens([e for a in seen for e in a.get("entities") or []])
        threshold = 0.86 if st.get("followed") else SIM_UPDATE_NOVEL
        for d in by_story[sid]:
            sim = float((M @ D.bin_to_vec(d["embedding"])).max())
            new_names = _keys(d) - seen_keys
            if sim < threshold and (new_names or st.get("followed")):
                db.stories.update_one({"_id": sid}, {"$set": {"pending_update_article_id": d["_id"]}})
                break


# ------------------------------------------------------------------ fetch pages
def decode_google(url: str) -> str | None:
    try:
        from googlenewsdecoder import gnewsdecoder
        res = gnewsdecoder(url, interval=None, timeout=10)
        if res.get("status") or res.get("success"):
            return res.get("decoded_url")
    except Exception as e:
        log.debug("decode failed %s", e)
    return None


BAD_IMG = re.compile(r"(logo|placeholder|default[-_.]|favicon|icon|avatar|sprite|blank|1x1|pixel)", re.I)


def image_info(client: httpx.Client, url: str) -> tuple[int, int, str] | None:
    from PIL import Image
    try:
        with client.stream("GET", url, timeout=10) as r:
            if r.status_code >= 400:
                return None
            buf = bytearray()
            for chunk in r.iter_bytes():
                buf.extend(chunk)
                if len(buf) > 4_000_000:
                    return None
        im = Image.open(io.BytesIO(buf))
        w, h = im.size
        im = im.convert("RGB")
        im.thumbnail((24, 24))
        out = io.BytesIO()
        im.save(out, "JPEG", quality=45)
        return w, h, "data:image/jpeg;base64," + base64.b64encode(out.getvalue()).decode()
    except Exception:
        return None


def fetch_article(client: httpx.Client, art: dict) -> dict:
    import trafilatura
    upd: dict = {"needs_fetch": False}
    url = art["url"]
    if "news.google.com" in url:
        real = decode_google(url)
        if real:
            upd["url_original"] = real
            url = real
        else:
            return upd
    html = None
    try:
        r = client.get(url, timeout=12)
        if r.status_code < 400 and "html" in r.headers.get("content-type", "html"):
            html = r.text
            url = str(r.url)
    except Exception:
        pass
    image = art.get("image")
    if html:
        try:
            body = trafilatura.extract(html, include_comments=False, include_tables=False, favor_precision=True) or ""
        except Exception:
            body = ""
        if len(body) > 400:
            upd["body"] = body[:15000]
            if not art.get("summary"):
                upd["summary"] = T.short_summary(body, 45)
        if not art.get("summary") and "summary" not in upd:
            m = re.search(r'<meta[^>]+(?:property|name)="(?:og:description|description)"[^>]+content="([^"]+)"', html)
            if m:
                upd["summary"] = T.short_summary(m.group(1), 45)
        m = re.search(r'<meta[^>]+property="og:image(?::url)?"[^>]+content="([^"]+)"', html) or \
            re.search(r'<meta[^>]+content="([^"]+)"[^>]+property="og:image"', html)
        if m and not BAD_IMG.search(m.group(1)):
            image = m.group(1).replace("&amp;", "&")
        upd["has_video"] = bool(re.search(r'og:video|<video|youtube\.com/embed|jwplayer', html))
        if url != art["url"]:
            upd["url_original"] = url
    if image and not BAD_IMG.search(image):
        info = image_info(client, image)
        if info:
            w, h, lqip = info
            ratio = w / max(h, 1)
            if w >= 300 and 0.6 <= ratio <= 2.6:
                upd.update({"image": image, "image_w": w, "image_h": h, "image_lqip": lqip})
            else:
                upd.update({"image": None})
    if upd.get("summary") or upd.get("body"):
        text = art["title"] + ". " + (upd.get("summary") or art.get("summary") or "") + " " + (upd.get("body") or "")[:1500]
        upd["entities"] = T.entities(art["title"] + ". " + (upd.get("summary") or art.get("summary") or ""))
        upd["places"] = T.find_places(text) or art["places"]
        upd["topics"] = T.tag_topics(text) or art["topics"]
    return upd


def fetch_pages(db, trust: dict) -> set[int]:
    since = D.now() - timedelta(hours=48)
    todo = list(db.articles.find({"needs_fetch": True, "published_at": {"$gte": since}})
                .sort("published_at", -1).limit(MAX_FETCH))
    touched: set[int] = set()
    if not todo:
        return touched
    prefs = D.get_prefs(db)
    done = 0
    ops: list = []
    with httpx.Client(headers={"User-Agent": UA, "Accept-Language": "en-IN,en;q=0.9"}, follow_redirects=True,
                      limits=httpx.Limits(max_connections=24)) as client:
        with ThreadPoolExecutor(12) as pool:
            futs = {pool.submit(fetch_article, client, a): a for a in todo}
            for fut in as_completed(futs):
                a = futs[fut]
                try:
                    upd = fut.result()
                except Exception as e:
                    log.debug("fetch failed %s %s", a["url"], e)
                    upd = {"needs_fetch": False}
                if "places" in upd:
                    upd["tier"] = T.story_tier(upd["places"], prefs, a.get("source_tier"))
                if upd.get("body"):
                    upd["body_len"] = len(upd["body"])
                ops.append(UpdateOne({"_id": a["_id"]}, {"$set": upd}))
                if len(ops) >= 100:
                    db.articles.bulk_write(ops, ordered=False)
                    ops = []
                if a.get("story_id"):
                    touched.add(a["story_id"])
                done += 1
                if elapsed() > TIME_BUDGET_S:
                    for f in futs:
                        f.cancel()
                    break
    if ops:
        db.articles.bulk_write(ops, ordered=False)
    refresh_stories(db, touched, trust)
    log.info("fetched %d article pages", done)
    return touched


# ------------------------------------------------------------------ threads
def build_threads(db) -> None:
    since = D.now() - timedelta(hours=48)
    stories = list(db.stories.find({"last_updated": {"$gte": since}}, {"centroid": 1, "entities": 1, "topics": 1, "thread_id": 1, "source_count": 1}))
    if len(stories) < 3:
        return
    toks = [T.key_tokens((s.get("entities") or [])[:8]) for s in stories]
    df = Counter(t for k in toks for t in k)
    limit = max(3, 0.03 * len(stories))
    keyn = [{t for t in k if df[t] <= limit} for k in toks]
    M = np.vstack([D.bin_to_vec(s["centroid"]) for s in stories])
    S = M @ M.T
    parent = list(range(len(stories)))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    for i in range(len(stories)):
        for j in np.where(S[i, i + 1:] >= SIM_THREAD)[0] + i + 1:
            shared = keyn[i] & keyn[j]
            if len(shared) >= 2 or (shared and set(stories[i]["topics"][:2]) & set(stories[j]["topics"][:2])):
                parent[find(i)] = find(int(j))
    groups: dict[int, list[int]] = {}
    for i in range(len(stories)):
        groups.setdefault(find(i), []).append(i)
    assigned = set()
    for members in groups.values():
        if len(members) < 3 or len(members) > 15:
            continue
        shared_keys = set().union(*(keyn[k] for k in members))
        names = Counter()
        for i in members:
            for e in (stories[i].get("entities") or [])[:6]:
                if T.key_tokens([e]) & shared_keys:
                    names[e] += 2 if " " in e else 1   # prefer full names ("Sheikh Hasina") over single words
        title = names.most_common(1)[0][0] if names else (stories[members[0]]["topics"] or ["Developing story"])[0]
        existing = Counter(stories[i].get("thread_id") for i in members if stories[i].get("thread_id"))
        if existing:
            tid = existing.most_common(1)[0][0]
            db.threads.update_one({"_id": tid}, {"$set": {"title": title, "updated_at": D.now()}}, upsert=True)
        else:
            tid = D.next_id(db, "threads")
            db.threads.insert_one({"_id": tid, "title": title, "created_at": D.now(), "updated_at": D.now()})
        ids = [stories[i]["_id"] for i in members]
        assigned.update(ids)
        db.stories.update_many({"_id": {"$in": ids}}, {"$set": {"thread_id": tid}})
    # stories that drifted out of their thread
    db.stories.update_many({"_id": {"$in": [s["_id"] for s in stories if s.get("thread_id") and s["_id"] not in assigned]}},
                           {"$set": {"thread_id": None}})


# ------------------------------------------------------------------ rules + prune
def embed_rules(db) -> None:
    todo = list(db.rules.find({"embedding": None}))
    if not todo:
        return
    vecs = embed([r["label"] for r in todo])
    for r, v in zip(todo, vecs):
        db.rules.update_one({"_id": r["_id"]}, {"$set": {"embedding": D.vec_to_bin(v)}})


def prune(db) -> None:
    keep = [s["_id"] for s in db.state.find({"$or": [{"status": "liked"}, {"saved": True}]}, {"_id": 1})]
    old = D.now() - timedelta(days=14)
    res = db.articles.delete_many({"published_at": {"$lt": old}, "story_id": {"$nin": keep}})
    db.articles.update_many({"published_at": {"$lt": D.now() - timedelta(days=5)}, "story_id": {"$nin": keep},
                             "body": {"$ne": None}}, {"$set": {"body": None}})
    live_ids = db.articles.distinct("story_id")
    db.stories.delete_many({"_id": {"$nin": live_ids}})
    db.state.delete_many({"_id": {"$nin": live_ids}})
    db.threads.delete_many({"updated_at": {"$lt": old}})
    db.interactions.delete_many({"created_at": {"$lt": D.now() - timedelta(days=365)}})
    if res.deleted_count:
        log.info("pruned %d old articles", res.deleted_count)


def recluster(db, trust: dict) -> None:
    """Dev helper: throw away stories that nobody has interacted with and cluster again."""
    keep = set(db.state.distinct("_id"))
    since = D.now() - timedelta(hours=WINDOW_H)
    docs = list(db.articles.find({"published_at": {"$gte": since}, "story_id": {"$nin": list(keep)}}, {"body": 0}))
    db.articles.update_many({"_id": {"$in": [d["_id"] for d in docs]}}, {"$set": {"story_id": None}})
    db.stories.delete_many({"_id": {"$nin": list(keep)}})
    db.stories.update_many({}, {"$set": {"thread_id": None}})
    db.threads.delete_many({})
    cluster(db, docs, trust)


def run(args: list[str]) -> None:
    db = D.get_db()
    D.ensure_indexes(db)
    trust = {s["_id"]: s.get("trust", 0.6) for s in db.sources.find({}, {"trust": 1})}
    if "--recluster" in args:
        recluster(db, trust)
        build_threads(db)
        return
    sync_sources(db)
    trust = {s["_id"]: s.get("trust", 0.6) for s in db.sources.find({}, {"trust": 1})}
    embed_rules(db)
    polled = poll_all(db)
    new_docs = insert_new(db, polled)
    cluster(db, new_docs, trust)
    fetch_pages(db, trust)
    build_threads(db)
    prune(db)
    D.set_setting(db, "last_ingest", {"at": D.now().isoformat(), "new": len(new_docs), "seconds": round(elapsed())})
    log.info("done in %.0fs", elapsed())


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    logging.getLogger("httpx").setLevel(logging.WARNING)
    logging.getLogger("trafilatura").setLevel(logging.ERROR)
    run(sys.argv[1:])
