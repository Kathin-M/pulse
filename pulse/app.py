"""Pulse API (FastAPI). Runs as a Vercel serverless function or under uvicorn."""
from __future__ import annotations

import hashlib
import hmac
import os
import re
import time
from collections import Counter, defaultdict
from datetime import datetime, timedelta
from pathlib import Path

import numpy as np
from fastapi import Depends, FastAPI, HTTPException, Request, Response
from fastapi.responses import JSONResponse
from itsdangerous import BadSignature, TimestampSigner
from pydantic import BaseModel

from . import db as D
from . import profile as P
from . import ranker
from .sources import city_sources
from .taxonomy import CITIES, TOPICS

app = FastAPI(title="Pulse", docs_url=None, redoc_url=None, openapi_url=None)

COOKIE = "pulse_session"
COOKIE_AGE = 60 * 60 * 24 * 365


# ------------------------------------------------------------------ auth
def _password() -> str | None:
    return os.environ.get("PULSE_PASSWORD") or None


def _signer() -> TimestampSigner:
    secret = os.environ.get("PULSE_SECRET")
    if not secret:
        # Derived fallback so a missing secret never means "no signing".
        secret = hashlib.sha256(("pulse:" + (_password() or "") + os.environ.get("MONGODB_URI", "")).encode()).hexdigest()
    return TimestampSigner(secret, salt="pulse-session")


def _hosted() -> bool:
    return bool(os.environ.get("VERCEL") or os.environ.get("RENDER") or os.environ.get("RAILWAY_ENVIRONMENT"))


def require_auth(request: Request) -> None:
    pw = _password()
    if not pw:
        if _hosted():
            raise HTTPException(503, "PULSE_PASSWORD is not configured on the server")
        return  # local dev without a password
    token = request.cookies.get(COOKIE)
    auth = request.headers.get("authorization", "")
    if not token and auth.lower().startswith("bearer "):
        token = auth[7:].strip()   # native app
    if not token:
        raise HTTPException(401, "login required")
    try:
        _signer().unsign(token, max_age=COOKIE_AGE)
    except BadSignature:
        raise HTTPException(401, "login required")


def get_db():
    return D.get_db()


class LoginIn(BaseModel):
    password: str


@app.post("/api/login")
def login(body: LoginIn, response: Response, request: Request):
    pw = _password()
    if not pw:
        if _hosted():
            raise HTTPException(503, "PULSE_PASSWORD is not configured on the server")
        return {"ok": True, "token": ""}
    if not hmac.compare_digest(body.password.encode(), pw.encode()):
        time.sleep(1.0)
        raise HTTPException(401, "wrong password")
    token = _signer().sign(b"owner").decode()
    if request.headers.get("x-pulse-client"):
        return {"ok": True, "token": token}   # native app keeps it in private storage
    response.set_cookie(COOKIE, token, max_age=COOKIE_AGE, httponly=True, secure=_hosted(), samesite="lax", path="/")
    return {"ok": True}


@app.post("/api/logout")
def logout(response: Response):
    response.delete_cookie(COOKIE, path="/")
    return {"ok": True}


@app.get("/api/health")
def health():
    return {"ok": True}


@app.get("/.well-known/assetlinks.json")
def assetlinks():
    """Digital Asset Links: lets the Android app (TWA) open this site full screen."""
    pkg = os.environ.get("ANDROID_PACKAGE")
    fps = [f.strip() for f in os.environ.get("ANDROID_CERT_SHA256", "").split(",") if f.strip()]
    if not pkg or not fps:
        return []
    return [{"relation": ["delegate_permission/common.handle_all_urls"],
             "target": {"namespace": "android_app", "package_name": pkg, "sha256_cert_fingerprints": fps}}]


@app.get("/api/me")
def me(request: Request):
    try:
        require_auth(request)
        return {"authed": True}
    except HTTPException as e:
        return JSONResponse({"authed": False, "detail": e.detail}, status_code=200)


AUTH = [Depends(require_auth)]


# ------------------------------------------------------------------ feed
@app.get("/api/feed", dependencies=AUTH)
def feed(mode: str = "foryou", exclude: str = "", limit: int = 60, db=Depends(get_db)):
    ex = {int(x) for x in exclude.split(",") if x.strip().lstrip("-").isdigit()}
    out = ranker.rank(db, mode=mode, limit=min(limit, 100), exclude=ex)
    prefs = D.get_prefs(db)
    out["onboarded"] = prefs.get("onboarded", False)
    out["last_ingest"] = D.get_setting(db, "last_ingest")
    return out


class InteractIn(BaseModel):
    story_id: int
    action: str                      # right | left | down | tap | read | save | unsave | like | unlike | follow | unfollow
    dwell_ms: int | None = None
    article_id: int | None = None
    story_ids: list[int] | None = None   # for thread cards


def _state(db, sid: int) -> dict:
    return db.state.find_one({"_id": sid}) or {"_id": sid, "status": "unseen", "saved": False, "followed": False,
                                                "seen_article_ids": []}


def _mark_seen(db, sid: int, status: str, now) -> dict:
    prev = _state(db, sid)
    art_ids = [a["_id"] for a in db.articles.find({"story_id": sid}, {"_id": 1})]
    upd = {"seen_article_ids": art_ids, "last_update_shown": now}
    upd["seen_at"] = now
    if status == "liked" or prev.get("status") != "liked":
        upd["status"] = status
    if status == "liked":
        upd["liked_at"] = now
    db.state.update_one({"_id": sid}, {"$set": upd}, upsert=True)
    db.stories.update_one({"_id": sid}, {"$set": {"pending_update_article_id": None}})
    prev.pop("_id", None)
    return prev


@app.post("/api/interact", dependencies=AUTH)
def interact(body: InteractIn, db=Depends(get_db)):
    now = D.now()
    sid = body.story_id
    a = body.action
    prev_states = {}
    weight = 0.0
    story_ids = body.story_ids or ([sid] if sid > 0 else [])

    if a in ("right", "left", "down"):
        if sid < 0 and a == "right":
            # Opening a thread: no state change, mild positive on its stories.
            weight = 0.3
        else:
            status = {"right": "liked", "left": "seen", "down": "hidden"}[a]
            for s in story_ids:
                prev_states[s] = _mark_seen(db, s, status, now)
            weight = P.signal_weight(a, body.dwell_ms)
    elif a in ("tap", "read"):
        weight = P.signal_weight(a, body.dwell_ms)
    elif a in ("save", "unsave"):
        prev_states[sid] = {k: v for k, v in _state(db, sid).items() if k != "_id"}
        db.state.update_one({"_id": sid}, {"$set": {"saved": a == "save", "saved_at": now}}, upsert=True)
    elif a in ("like", "unlike"):
        prev_states[sid] = {k: v for k, v in _state(db, sid).items() if k != "_id"}
        if a == "like":
            db.state.update_one({"_id": sid}, {"$set": {"status": "liked", "liked_at": now}}, upsert=True)
            weight = 1.0
        else:
            db.state.update_one({"_id": sid}, {"$set": {"status": "seen"}}, upsert=True)
            weight = -0.5
    elif a in ("follow", "unfollow"):
        db.state.update_one({"_id": sid}, {"$set": {"followed": a == "follow"}}, upsert=True)
    else:
        raise HTTPException(400, "unknown action")

    if weight:
        for s in story_ids:
            story = db.stories.find_one({"_id": s}, {"centroid": 1, "topics": 1, "entities": 1, "lead_article_id": 1})
            if story:
                # thread swipes spread the signal thinly over the thread
                P.apply_signal(db, story, weight / max(1, len(story_ids)) if len(story_ids) > 1 else weight, at=now)
                P.nudge_source(db, story, weight)

    iid = D.next_id(db, "interactions")
    db.interactions.insert_one({
        "_id": iid, "story_id": sid, "article_id": body.article_id, "action": a, "dwell_ms": body.dwell_ms,
        "weight": weight, "meta": {"prev": {str(k): v for k, v in prev_states.items()}, "story_ids": story_ids},
        "undone": False, "created_at": now,
    })
    return {"ok": True, "interaction_id": iid, "weight": weight}


class UndoIn(BaseModel):
    interaction_id: int


@app.post("/api/undo", dependencies=AUTH)
def undo(body: UndoIn, db=Depends(get_db)):
    it = db.interactions.find_one({"_id": body.interaction_id})
    if not it or it.get("undone"):
        raise HTTPException(404, "nothing to undo")
    for sid_s, prev in (it.get("meta", {}).get("prev") or {}).items():
        sid = int(sid_s)
        if prev and prev.get("status") is not None:
            db.state.update_one({"_id": sid}, {"$set": prev}, upsert=True)
        else:
            db.state.delete_one({"_id": sid})
    w = it.get("weight") or 0
    if w:
        for s in it.get("meta", {}).get("story_ids") or [it["story_id"]]:
            story = db.stories.find_one({"_id": s}, {"centroid": 1, "topics": 1, "entities": 1})
            if story:
                # Inverse nudge; an exact rollback comes from "Rebuild profile".
                P.apply_signal(db, story, -w * 0.9)
    db.interactions.update_one({"_id": it["_id"]}, {"$set": {"undone": True}})
    return {"ok": True}


# ------------------------------------------------------------------ story detail
NUM_RE = re.compile(r"(?:₹|Rs\.?|\$)?\s?\d[\d,.]*\s?(?:crore|lakh|%|per cent|km|million|billion|mw|kg|tonnes)?", re.I)


def _facts(a: dict) -> tuple[set, set]:
    text = (a.get("summary") or "") + " " + (a.get("body") or "")[:2500]
    nums = {n.strip() for n in NUM_RE.findall(text) if len(n.strip()) >= 2 and not re.fullmatch(r"20\d\d", n.strip())}
    ents = set(a.get("entities") or [])
    return nums, ents


@app.get("/api/story/{sid}", dependencies=AUTH)
def story_detail(sid: int, db=Depends(get_db)):
    story = db.stories.find_one({"_id": sid})
    if not story:
        raise HTTPException(404, "story not found")
    arts = list(db.articles.find({"story_id": sid}, {"body": 0}))
    sources = {s["_id"]: s for s in db.sources.find({}, {"trust": 1, "name": 1})}
    state = _state(db, sid)
    card = ranker.build_card(story, arts, sources, set(), state)
    lead_id = card["article_id"] if card else None
    lead = db.articles.find_one({"_id": lead_id}) if lead_id else None
    lead_nums, lead_ents = _facts(lead) if lead else (set(), set())

    reports = sorted([a for a in arts if a["kind"] in ("report", "video")], key=lambda a: a["published_at"])
    # Timeline: keep a headline only if it says something the earlier ones didn't.
    timeline, kept = [], []
    for a in reports:
        v = D.bin_to_vec(a["embedding"])
        if all(float(v @ k) < 0.85 for k in kept):
            kept.append(v)
            timeline.append({"time": D.aware(a["published_at"]).isoformat(), "title": a["title"], "publisher": a["publisher"]})
    timeline = timeline[-8:]

    seen_pub = set()
    src_rows, tucked = [], []
    for a in sorted(reports, key=lambda a: (a["_id"] != lead_id, -sources.get(a.get("source_id"), {}).get("trust", 0.6))):
        if a["publisher"] in seen_pub:
            continue
        seen_pub.add(a["publisher"])
        full = db.articles.find_one({"_id": a["_id"]}, {"body": 1}) or {}
        a2 = {**a, "body": full.get("body")}
        if a["_id"] == lead_id:
            note = "Lead source · full report" if a2.get("body") else "Lead source"
        else:
            nums, ents = _facts(a2)
            new_nums = [n for n in nums - lead_nums][:2]
            new_ents = [e for e in ents - lead_ents][:2]
            if a.get("has_video"):
                note = "Adds: video"
            elif new_nums:
                note = "Adds figures: " + ", ".join(new_nums)
            elif new_ents:
                note = "Adds: " + ", ".join(new_ents)
            else:
                note = "Same facts, nothing new"
        row = {"article_id": a["_id"], "publisher": a["publisher"], "title": a["title"],
               "url": a.get("url_original") or a["url"], "time": D.aware(a["published_at"]).isoformat(),
               "note": note, "has_body": bool(a2.get("body"))}
        (tucked if note.startswith("Same facts") else src_rows).append(row)

    explainers = [{"article_id": a["_id"], "publisher": a["publisher"], "title": a["title"],
                   "url": a.get("url_original") or a["url"], "time": D.aware(a["published_at"]).isoformat(),
                   "kind": a["kind"]} for a in arts if a["kind"] in ("opinion", "live")]
    thread = None
    if story.get("thread_id"):
        t = db.threads.find_one({"_id": story["thread_id"]})
        if t:
            thread = {"id": t["_id"], "title": t["title"], "count": db.stories.count_documents({"thread_id": t["_id"]})}
    return {"card": card, "timeline": timeline, "sources": src_rows, "more_sources": tucked,
            "explainers": explainers, "thread": thread, "followed": bool(state.get("followed")),
            "liked": state.get("status") == "liked", "saved": bool(state.get("saved")),
            "article_count": len(arts)}


@app.post("/api/story/{sid}/split", dependencies=AUTH)
def split_story(sid: int, db=Depends(get_db)):
    """'These are different stories': re-cluster this story's articles with a much stricter threshold."""
    arts = list(db.articles.find({"story_id": sid}, {"embedding": 1, "published_at": 1}))
    if len(arts) < 2:
        raise HTTPException(400, "nothing to split")
    vecs = [D.bin_to_vec(a["embedding"]) for a in arts]
    groups: list[list[int]] = []
    for i, v in enumerate(vecs):
        for g in groups:
            if float(np.mean([vecs[j] @ v for j in g])) >= 0.88:
                g.append(i)
                break
        else:
            groups.append([i])
    if len(groups) == 1:
        # Fall back to two halves by similarity to the first article.
        sims = [float(vecs[0] @ v) for v in vecs]
        cut = float(np.median(sims))
        groups = [[i for i, s in enumerate(sims) if s >= cut], [i for i, s in enumerate(sims) if s < cut]]
        groups = [g for g in groups if g]
    new_ids = []
    for g in groups[1:]:
        nid = D.next_id(db, "stories")
        first = arts[g[0]]
        db.stories.insert_one({"_id": nid, "centroid": D.vec_to_bin(D.normalize(np.mean([vecs[i] for i in g], axis=0))),
                               "first_seen": first["published_at"], "last_updated": first["published_at"],
                               "source_count": 1, "topics": [], "places": [], "entities": [], "tier": None,
                               "lead_article_id": first["_id"], "thread_id": None,
                               "pending_update_article_id": None, "no_merge": True})
        db.articles.update_many({"_id": {"$in": [arts[i]["_id"] for i in g]}}, {"$set": {"story_id": nid}})
        new_ids.append(nid)
    db.stories.update_one({"_id": sid}, {"$set": {"no_merge": True}})
    for s in [sid, *new_ids]:
        refresh_story_light(db, s)
    return {"ok": True, "stories": [sid, *new_ids]}


def refresh_story_light(db, sid: int) -> None:
    arts = list(db.articles.find({"story_id": sid}, {"body": 0}))
    if not arts:
        db.stories.delete_one({"_id": sid})
        return
    vecs = np.vstack([D.bin_to_vec(a["embedding"]) for a in arts])
    topics = Counter(t for a in arts for t in a["topics"])
    places = Counter(p for a in arts for p in a["places"])
    ents = Counter(e for a in arts for e in a["entities"])
    lead = max(arts, key=lambda a: len(a.get("summary") or ""))
    db.stories.update_one({"_id": sid}, {"$set": {
        "centroid": D.vec_to_bin(D.normalize(vecs.mean(axis=0))),
        "first_seen": min(D.aware(a["published_at"]) for a in arts),
        "last_updated": max(D.aware(a["published_at"]) for a in arts),
        "source_count": len({a["publisher"] for a in arts}),
        "topics": [t for t, _ in topics.most_common(3)], "places": [p for p, _ in places.most_common(4)],
        "entities": [e for e, _ in ents.most_common(12)], "lead_article_id": lead["_id"],
        "tier": Counter(a.get("tier") for a in arts).most_common(1)[0][0],
    }})


@app.get("/api/thread/{tid}", dependencies=AUTH)
def thread_cards(tid: int, db=Depends(get_db)):
    t = db.threads.find_one({"_id": tid})
    if not t:
        raise HTTPException(404, "thread not found")
    stories = list(db.stories.find({"thread_id": tid}).sort("last_updated", -1))
    states = {s["_id"]: s for s in db.state.find({"_id": {"$in": [s["_id"] for s in stories]}})}
    sources = {s["_id"]: s for s in db.sources.find({}, {"trust": 1, "name": 1})}
    cards = []
    for s in stories:
        st = states.get(s["_id"]) or {}
        if st.get("status") in ("seen", "liked", "hidden"):
            continue
        arts = list(db.articles.find({"story_id": s["_id"]}, {"embedding": 0, "body": 0}))
        c = ranker.build_card(s, arts, sources, set(), st)
        if c:
            c["why"] = f"Part of {t['title']}"
            cards.append(c)
    return {"title": t["title"], "cards": cards}


@app.get("/api/article/{aid}/reader", dependencies=AUTH)
def reader(aid: int, db=Depends(get_db)):
    a = db.articles.find_one({"_id": aid}, {"embedding": 0})
    if not a:
        raise HTTPException(404, "article not found")
    body = a.get("body")
    url = a.get("url_original") or a["url"]
    if not body and "news.google.com" not in url:
        try:
            import httpx
            import trafilatura
            r = httpx.get(url, timeout=8, follow_redirects=True,
                          headers={"User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/131.0 Safari/537.36"})
            if r.status_code < 400:
                body = trafilatura.extract(r.text, include_comments=False, favor_precision=True)
                if body:
                    db.articles.update_one({"_id": aid}, {"$set": {"body": body[:15000]}})
        except Exception:
            body = None
    return {"title": a["title"], "publisher": a["publisher"], "url": url, "image": a.get("image"),
            "published_at": D.aware(a["published_at"]).isoformat(),
            "paragraphs": [p.strip() for p in (body or "").split("\n") if p.strip()],
            "summary": a.get("summary"), "has_body": bool(body)}


# ------------------------------------------------------------------ history
def _history_cards(db, query: dict, q: str = "", topic: str = "", source: str = "", limit: int = 300):
    states = list(db.state.find(query).sort("seen_at", -1).limit(1500))
    ids = [s["_id"] for s in states]
    stories = {s["_id"]: s for s in db.stories.find({"_id": {"$in": ids}}, {"centroid": 0})}
    arts = defaultdict(list)
    for a in db.articles.find({"story_id": {"$in": ids}}, {"embedding": 0, "body": 0}):
        arts[a["story_id"]].append(a)
    sources = {s["_id"]: s for s in db.sources.find({}, {"trust": 1, "name": 1})}
    out = []
    ql = q.lower().strip()
    for st in states:
        s = stories.get(st["_id"])
        if not s or not arts.get(st["_id"]):
            continue
        card = ranker.build_card(s, arts[st["_id"]], sources, set(), st)
        if not card:
            continue
        if ql and ql not in (card["title"] + " " + (card["summary"] or "")).lower():
            continue
        if topic and topic not in card["topics"]:
            continue
        if source and source not in {a["publisher"] for a in arts[st["_id"]]}:
            continue
        when = st.get("liked_at") if query.get("$or") else st.get("seen_at")
        card["when"] = D.aware(when or st.get("seen_at") or s["last_updated"]).isoformat()
        card["status"] = st.get("status")
        out.append(card)
        if len(out) >= limit:
            break
    return out


@app.get("/api/seen", dependencies=AUTH)
def seen(q: str = "", topic: str = "", source: str = "", db=Depends(get_db)):
    cards = _history_cards(db, {"seen_at": {"$ne": None}}, q, topic, source)
    return {"cards": cards, "topics": sorted({t for c in cards for t in c["topics"]}),
            "sources": sorted({c["publisher"] for c in cards})}


@app.get("/api/liked", dependencies=AUTH)
def liked(q: str = "", db=Depends(get_db)):
    cards = _history_cards(db, {"$or": [{"status": "liked"}, {"saved": True}]}, q)
    cards.sort(key=lambda c: c["when"], reverse=True)
    return {"cards": cards}


# ------------------------------------------------------------------ controls
@app.get("/api/controls", dependencies=AUTH)
def controls(db=Depends(get_db)):
    prefs = D.get_prefs(db)
    rules = [{"id": r["_id"], "label": r["label"], "kind": r["kind"], "mode": r["mode"], "weight": r.get("weight", 1.0)}
             for r in db.rules.find({}, {"embedding": 0}).sort("created_at", 1)]
    week = D.now() - timedelta(days=7)
    srcs = []
    counts = Counter()
    for a in db.articles.find({"published_at": {"$gte": D.now() - timedelta(days=3)}}, {"source_id": 1}):
        counts[a.get("source_id")] += 1
    for s in db.sources.find({}).sort([("kind", 1), ("name", 1)]):
        last_new = D.aware(s.get("last_new_at"))
        created = D.aware(s.get("created_at"))
        dead = bool(s.get("last_polled")) and (last_new is None or last_new < week) and (created is None or created < week)
        srcs.append({"id": s["_id"], "name": s["name"], "url": s["url"], "kind": s["kind"], "trust": s.get("trust", 0.6),
                     "enabled": s.get("enabled", True), "error": s.get("last_error"), "dead": dead,
                     "learned_bias": round(s.get("learned_bias", 0.0), 2), "articles_3d": counts.get(s["_id"], 0),
                     "last_polled": D.aware(s["last_polled"]).isoformat() if s.get("last_polled") else None})
    return {"prefs": prefs, "rules": rules, "sources": srcs, "topics": list(TOPICS.keys()),
            "cities": [{"name": c, "state": v[0]} for c, v in CITIES.items()],
            "last_ingest": D.get_setting(db, "last_ingest")}


class PrefsIn(BaseModel):
    city: str | None = None
    state: str | None = None
    tiers: dict[str, float] | None = None
    discovery: float | None = None
    paused_topics: list[str] | None = None
    onboarded: bool | None = None


@app.put("/api/prefs", dependencies=AUTH)
def put_prefs(body: PrefsIn, db=Depends(get_db)):
    prefs = D.get_prefs(db)
    data = body.model_dump(exclude_none=True)
    if "city" in data and data["city"] in CITIES and "state" not in data:
        data["state"] = CITIES[data["city"]][0]
    if "discovery" in data:
        data["discovery"] = max(0.0, min(0.5, data["discovery"]))
    if "tiers" in data:
        data["tiers"] = {k: max(0.0, min(1.0, float(v))) for k, v in {**prefs["tiers"], **data["tiers"]}.items()}
    prefs.update(data)
    D.set_setting(db, "prefs", prefs)
    if "city" in data:
        _sync_city_sources(db, prefs)
    return prefs


def _sync_city_sources(db, prefs):
    wanted = city_sources(prefs["city"], prefs["state"])
    for s in wanted:
        db.sources.update_one({"url": s["url"]}, {"$set": {"name": s["name"], "kind": "city", "tier": s["tier"], "query": s.get("query")},
                                                  "$setOnInsert": {"_id": D.next_id(db, "sources"), "trust": s["trust"],
                                                                   "learned_bias": 0.0, "enabled": True, "created_at": D.now()}},
                              upsert=True)
    db.sources.delete_many({"kind": "city", "url": {"$nin": [s["url"] for s in wanted]}})


class OnboardIn(BaseModel):
    city: str
    topics: list[str]


@app.post("/api/onboard", dependencies=AUTH)
def onboard(body: OnboardIn, db=Depends(get_db)):
    prefs = D.get_prefs(db)
    prefs["city"] = body.city
    prefs["state"] = CITIES.get(body.city, (prefs["state"],))[0]
    prefs["onboarded"] = True
    D.set_setting(db, "prefs", prefs)
    _sync_city_sources(db, prefs)
    for t in body.topics:
        if t in TOPICS:
            db.rules.update_one({"kind": "topic", "label": t},
                                {"$set": {"mode": "follow", "weight": 1.0},
                                 "$setOnInsert": {"_id": D.next_id(db, "rules"), "embedding": None, "created_at": D.now()}},
                                upsert=True)
    return {"ok": True}


class RuleIn(BaseModel):
    label: str
    kind: str = "keyword"   # topic | keyword | source | place
    mode: str = "follow"    # follow | mute | always | neutral


@app.post("/api/rules", dependencies=AUTH)
def add_rule(body: RuleIn, db=Depends(get_db)):
    label = body.label.strip()[:80]
    if not label or body.kind not in ("topic", "keyword", "source", "place"):
        raise HTTPException(400, "bad rule")
    if body.mode == "neutral":
        db.rules.delete_one({"kind": body.kind, "label": label})
        return {"ok": True}
    if body.mode not in ("follow", "mute", "always"):
        raise HTTPException(400, "bad mode")
    db.rules.update_one({"kind": body.kind, "label": label},
                        {"$set": {"mode": body.mode, "weight": 1.0},
                         "$setOnInsert": {"_id": D.next_id(db, "rules"), "embedding": None, "created_at": D.now()}},
                        upsert=True)
    return {"ok": True}


@app.delete("/api/rules/{rid}", dependencies=AUTH)
def delete_rule(rid: int, db=Depends(get_db)):
    db.rules.delete_one({"_id": rid})
    return {"ok": True}


class SourceIn(BaseModel):
    url: str
    name: str | None = None


@app.post("/api/sources", dependencies=AUTH)
def add_source(body: SourceIn, db=Depends(get_db)):
    url = body.url.strip()
    if not re.match(r"^https?://", url):
        raise HTTPException(400, "URL must start with http(s)://")
    name = (body.name or "").strip() or re.sub(r"^https?://(www\.)?", "", url).split("/")[0]
    db.sources.update_one({"url": url}, {"$set": {"name": name, "kind": "custom"},
                                         "$setOnInsert": {"_id": D.next_id(db, "sources"), "trust": 0.6, "tier": None,
                                                          "learned_bias": 0.0, "enabled": True, "created_at": D.now()}},
                          upsert=True)
    return {"ok": True}


class SourcePatch(BaseModel):
    trust: float | None = None
    enabled: bool | None = None


@app.patch("/api/sources/{source_id}", dependencies=AUTH)
def patch_source(source_id: int, body: SourcePatch, db=Depends(get_db)):
    upd = body.model_dump(exclude_none=True)
    if "trust" in upd:
        upd["trust"] = max(0.0, min(1.0, upd["trust"]))
    db.sources.update_one({"_id": source_id}, {"$set": upd})
    return {"ok": True}


@app.delete("/api/sources/{source_id}", dependencies=AUTH)
def delete_source(source_id: int, db=Depends(get_db)):
    db.sources.delete_one({"_id": source_id})
    return {"ok": True}


# ------------------------------------------------------------------ insights
@app.get("/api/insights", dependencies=AUTH)
def insights(db=Depends(get_db)):
    now = D.now()
    out = []
    for c in db.clusters.find({}):
        w = P.effective_weight(c, now)
        out.append({"id": c["_id"], "label": P.cluster_label(c), "polarity": c["polarity"], "weight": round(w, 2),
                    "strength": round(w / (w + 2), 2), "signals": c.get("signals", 1),
                    "updated_at": D.aware(c["updated_at"]).isoformat()})
    out.sort(key=lambda c: (-c["polarity"], -c["weight"]))
    prefs = D.get_prefs(db)
    return {"clusters": out, "paused_topics": prefs.get("paused_topics", []), "topics": list(TOPICS.keys()),
            "interactions": db.interactions.count_documents({"undone": False})}


@app.delete("/api/insights/{cid}", dependencies=AUTH)
def forget_cluster(cid: int, db=Depends(get_db)):
    db.clusters.delete_one({"_id": cid})
    return {"ok": True}


class TopicIn(BaseModel):
    topic: str


@app.post("/api/insights/reset-topic", dependencies=AUTH)
def reset_topic(body: TopicIn, db=Depends(get_db)):
    removed = 0
    for c in db.clusters.find({}):
        top = Counter(c.get("topics") or {}).most_common(1)
        if top and top[0][0] == body.topic:
            db.clusters.delete_one({"_id": c["_id"]})
            removed += 1
    return {"ok": True, "removed": removed}


@app.post("/api/insights/rebuild", dependencies=AUTH)
def rebuild(db=Depends(get_db)):
    return {"ok": True, "replayed": P.rebuild(db)}


@app.get("/api/session-summary", dependencies=AUTH)
def session_summary(since: str, db=Depends(get_db)):
    try:
        t = datetime.fromisoformat(since.replace("Z", "+00:00"))
    except ValueError:
        raise HTTPException(400, "bad since")
    per_topic = Counter()
    n = 0
    for it in db.interactions.find({"created_at": {"$gte": t}, "undone": False}):
        n += 1
        w = it.get("weight") or 0
        if not w:
            continue
        s = db.stories.find_one({"_id": it["story_id"]}, {"topics": 1})
        if s and s.get("topics"):
            per_topic[s["topics"][0]] += w
    more = [k for k, v in per_topic.most_common() if v > 0.5][:2]
    less = [k for k, v in sorted(per_topic.items(), key=lambda kv: kv[1]) if v < -0.5][:2]
    parts = []
    if more:
        parts.append("more " + " and ".join(more))
    if less:
        parts.append("less " + " and ".join(less))
    return {"note": ("Learned: " + ", ".join(parts)) if parts else None, "interactions": n}


# ------------------------------------------------------------------ static files (self-hosting only)
_dist = Path(__file__).resolve().parent.parent / "web" / "dist"
if _dist.exists() and not os.environ.get("VERCEL"):
    from fastapi.responses import FileResponse
    from fastapi.staticfiles import StaticFiles

    app.mount("/assets", StaticFiles(directory=_dist / "assets"), name="assets")

    @app.get("/{path:path}")
    def spa(path: str):
        f = _dist / path
        if path and f.is_file():
            return FileResponse(f)
        return FileResponse(_dist / "index.html")
