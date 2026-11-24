"""Pulse API (FastAPI). Multi-user: each account has its own feed state, history and learned profile.

Runs as a Vercel serverless function or under uvicorn.
"""
from __future__ import annotations

import os
import re
import time
from collections import Counter, defaultdict
from datetime import datetime, timedelta
from pathlib import Path

import numpy as np
from fastapi import Depends, FastAPI, HTTPException, Request, Response
from pydantic import BaseModel

from . import auth as A
from . import db as D
from . import profile as P
from . import ranker
from .taxonomy import CITIES, TOPICS, user_tier

app = FastAPI(title="Pulse", docs_url=None, redoc_url=None, openapi_url=None)

COOKIE = "pulse_session"


def _hosted() -> bool:
    return bool(os.environ.get("VERCEL") or os.environ.get("RENDER") or os.environ.get("RAILWAY_ENVIRONMENT"))


def get_db():
    return D.get_db()


def _token_from(request: Request) -> str | None:
    token = request.cookies.get(COOKIE)
    auth = request.headers.get("authorization", "")
    if auth.lower().startswith("bearer "):
        token = auth[7:].strip()   # native app
    return token


def current_user(request: Request) -> int:
    uid = A.read_token(_token_from(request))
    if uid is None:
        raise HTTPException(401, "login required")
    return uid


# ------------------------------------------------------------------ accounts
class AuthIn(BaseModel):
    username: str
    password: str


def _session(response: Response, request: Request, uid: int, username: str) -> dict:
    token = A.make_token(uid)
    out = {"ok": True, "username": username}
    if request.headers.get("x-pulse-client"):
        out["token"] = token   # native app keeps it in private storage
    else:
        response.set_cookie(COOKIE, token, max_age=A.TOKEN_AGE, httponly=True, secure=_hosted(), samesite="lax", path="/")
    return out


@app.post("/api/signup")
def signup(body: AuthIn, response: Response, request: Request, db=Depends(get_db)):
    username = A.normalize_username(body.username)
    err = A.validate_new(username, body.password)
    if err:
        raise HTTPException(400, err)
    if db.users.find_one({"username": username}):
        raise HTTPException(409, "That username is taken")
    err = A.signup_allowed(db)
    if err:
        raise HTTPException(429, err)
    uid = A.create_user(db, username, body.password)
    return _session(response, request, uid, username)


@app.post("/api/login")
def login(body: AuthIn, response: Response, request: Request, db=Depends(get_db)):
    username = A.normalize_username(body.username)
    user = db.users.find_one({"username": username})
    if not user or not A.check_password(body.password, user["password"]):
        time.sleep(0.8)
        raise HTTPException(401, "Wrong username or password")
    return _session(response, request, user["_id"], username)


@app.post("/api/logout")
def logout(response: Response):
    response.delete_cookie(COOKIE, path="/")
    return {"ok": True}


@app.get("/api/me")
def me(request: Request, db=Depends(get_db)):
    uid = A.read_token(_token_from(request))
    user = db.users.find_one({"_id": uid}, {"username": 1}) if uid is not None else None
    if not user:
        return {"authed": False}
    return {"authed": True, "username": user["username"]}


class PasswordIn(BaseModel):
    current: str
    new: str


@app.post("/api/account/password")
def change_password(body: PasswordIn, uid: int = Depends(current_user), db=Depends(get_db)):
    user = db.users.find_one({"_id": uid})
    if not user or not A.check_password(body.current, user["password"]):
        raise HTTPException(401, "Current password is wrong")
    err = A.validate_new(user["username"], body.new)
    if err:
        raise HTTPException(400, err)
    db.users.update_one({"_id": uid}, {"$set": {"password": A.hash_password(body.new)}})
    return {"ok": True}


@app.get("/api/health")
def health():
    return {"ok": True}


@app.get("/.well-known/assetlinks.json")
def assetlinks():
    """Digital Asset Links for the Android app's package."""
    pkg = os.environ.get("ANDROID_PACKAGE")
    fps = [f.strip() for f in os.environ.get("ANDROID_CERT_SHA256", "").split(",") if f.strip()]
    if not pkg or not fps:
        return []
    return [{"relation": ["delegate_permission/common.handle_all_urls"],
             "target": {"namespace": "android_app", "package_name": pkg, "sha256_cert_fingerprints": fps}}]


# ------------------------------------------------------------------ feed
@app.get("/api/feed")
def feed(mode: str = "foryou", exclude: str = "", limit: int = 60, uid: int = Depends(current_user), db=Depends(get_db)):
    ex = {int(x) for x in exclude.split(",") if x.strip().lstrip("-").isdigit()}
    out = ranker.rank(db, uid, mode=mode, limit=min(limit, 100), exclude=ex)
    out["onboarded"] = D.get_prefs(db, uid).get("onboarded", False)
    out["last_ingest"] = D.get_setting(db, "last_ingest")
    return out


class InteractIn(BaseModel):
    story_id: int
    action: str                          # right | left | up | down | tap | read | save | unsave | like | unlike | follow | unfollow | share
    dwell_ms: int | None = None
    article_id: int | None = None
    story_ids: list[int] | None = None   # thread cards


STATUS_FOR = {"right": "liked", "left": "seen", "up": "fine", "down": "hidden"}


def _state(db, uid: int, sid: int) -> dict:
    return db.state.find_one({"_id": D.state_id(uid, sid)}) or {
        "_id": D.state_id(uid, sid), "uid": uid, "story_id": sid, "status": "unseen",
        "saved": False, "followed": False, "seen_article_ids": []}


def _prev(st: dict) -> dict:
    return {k: v for k, v in st.items() if k not in ("_id", "uid", "story_id")}


def _set_state(db, uid: int, sid: int, fields: dict) -> None:
    db.state.update_one({"_id": D.state_id(uid, sid)}, {"$set": {"uid": uid, "story_id": sid, **fields}}, upsert=True)


def _mark_seen(db, uid: int, sid: int, status: str, now) -> dict:
    prev = _state(db, uid, sid)
    art_ids = [a["_id"] for a in db.articles.find({"story_id": sid}, {"_id": 1})]
    upd = {"seen_article_ids": art_ids, "last_update_shown": now, "seen_at": now, "pending_update_article_id": None}
    if status == "liked" or prev.get("status") != "liked":
        upd["status"] = status
    if status == "liked":
        upd["liked_at"] = now
    _set_state(db, uid, sid, upd)
    return _prev(prev)


@app.post("/api/interact")
def interact(body: InteractIn, uid: int = Depends(current_user), db=Depends(get_db)):
    now = D.now()
    sid = body.story_id
    a = body.action
    prev_states: dict[int, dict] = {}
    weight = 0.0
    story_ids = body.story_ids or ([sid] if sid > 0 else [])

    if a in STATUS_FOR:
        if sid < 0 and a == "right":
            weight = 0.3   # opening a thread: no state change, mild positive
        else:
            for s in story_ids:
                prev_states[s] = _mark_seen(db, uid, s, STATUS_FOR[a], now)
            weight = P.signal_weight(a, body.dwell_ms)
    elif a in ("tap", "read"):
        weight = P.signal_weight(a, body.dwell_ms)
    elif a in ("save", "unsave"):
        prev_states[sid] = _prev(_state(db, uid, sid))
        _set_state(db, uid, sid, {"saved": a == "save", "saved_at": now})
    elif a in ("like", "unlike"):
        prev_states[sid] = _prev(_state(db, uid, sid))
        if a == "like":
            _set_state(db, uid, sid, {"status": "liked", "liked_at": now})
            weight = 1.0
        else:
            _set_state(db, uid, sid, {"status": "seen"})
            weight = -0.5
    elif a in ("follow", "unfollow"):
        _set_state(db, uid, sid, {"followed": a == "follow"})
    elif a == "share":
        weight = 0.5   # sharing is a strong "this matters"
    else:
        raise HTTPException(400, "unknown action")

    if weight:
        prefs = D.get_prefs(db, uid)
        for s in story_ids:
            story = db.stories.find_one({"_id": s}, {"centroid": 1, "topics": 1, "entities": 1, "lead_article_id": 1})
            if story:
                w = weight / len(story_ids) if len(story_ids) > 1 else weight
                P.apply_signal(db, uid, story, w, at=now, prefs=prefs)
                P.nudge_source(db, uid, story, weight)

    iid = D.next_id(db, "interactions")
    db.interactions.insert_one({
        "_id": iid, "uid": uid, "story_id": sid, "article_id": body.article_id, "action": a, "dwell_ms": body.dwell_ms,
        "weight": weight, "meta": {"prev": {str(k): v for k, v in prev_states.items()}, "story_ids": story_ids},
        "undone": False, "created_at": now,
    })
    return {"ok": True, "interaction_id": iid, "weight": weight}


class UndoIn(BaseModel):
    interaction_id: int


@app.post("/api/undo")
def undo(body: UndoIn, uid: int = Depends(current_user), db=Depends(get_db)):
    it = db.interactions.find_one({"_id": body.interaction_id, "uid": uid})
    if not it or it.get("undone"):
        raise HTTPException(404, "nothing to undo")
    for sid_s, prev in (it.get("meta", {}).get("prev") or {}).items():
        sid = int(sid_s)
        if prev and prev.get("status") is not None:
            _set_state(db, uid, sid, prev)
        else:
            db.state.delete_one({"_id": D.state_id(uid, sid)})
    w = it.get("weight") or 0
    if w:
        for s in it.get("meta", {}).get("story_ids") or [it["story_id"]]:
            story = db.stories.find_one({"_id": s}, {"centroid": 1, "topics": 1, "entities": 1})
            if story:
                P.apply_signal(db, uid, story, -w * 0.9)   # exact rollback comes from "Rebuild profile"
    db.interactions.update_one({"_id": it["_id"]}, {"$set": {"undone": True}})
    return {"ok": True}


# ------------------------------------------------------------------ story detail
NUM_RE = re.compile(r"(?:₹|Rs\.?|\$)?\s?\d[\d,.]*\s?(?:crore|lakh|%|per cent|km|million|billion|mw|kg|tonnes)?", re.I)


def _facts(a: dict) -> tuple[set, set]:
    text = (a.get("summary") or "") + " " + (a.get("body") or "")[:2500]
    nums = {n.strip() for n in NUM_RE.findall(text) if len(n.strip()) >= 2 and not re.fullmatch(r"20\d\d", n.strip())}
    return nums, set(a.get("entities") or [])


@app.get("/api/story/{sid}")
def story_detail(sid: int, uid: int = Depends(current_user), db=Depends(get_db)):
    story = db.stories.find_one({"_id": sid})
    if not story:
        raise HTTPException(404, "story not found")
    arts = list(db.articles.find({"story_id": sid}, {"body": 0}))
    sources = ranker.user_sources(db, uid)
    state = _state(db, uid, sid)
    card = ranker.build_card(story, arts, sources, set(), state)
    lead_id = card["article_id"] if card else None
    lead = db.articles.find_one({"_id": lead_id}) if lead_id else None
    lead_nums, lead_ents = _facts(lead) if lead else (set(), set())

    reports = sorted([a for a in arts if a["kind"] in ("report", "video")], key=lambda a: a["published_at"])
    timeline, kept = [], []
    for a in reports:   # keep a headline only if it says something the earlier ones didn't
        v = D.bin_to_vec(a["embedding"])
        if all(float(v @ k) < 0.85 for k in kept):
            kept.append(v)
            timeline.append({"time": D.aware(a["published_at"]).isoformat(), "title": a["title"], "publisher": a["publisher"]})
    timeline = timeline[-8:]

    bodies = {a["_id"]: a.get("body") for a in db.articles.find({"story_id": sid}, {"body": 1})}
    seen_pub, src_rows, tucked = set(), [], []
    for a in sorted(reports, key=lambda a: (a["_id"] != lead_id, -sources.get(a.get("source_id"), {}).get("trust", 0.6))):
        if a["publisher"] in seen_pub:
            continue
        seen_pub.add(a["publisher"])
        a2 = {**a, "body": bodies.get(a["_id"])}
        if a["_id"] == lead_id:
            note = "Lead source · full report" if a2.get("body") else "Lead source"
        else:
            nums, ents = _facts(a2)
            new_nums, new_ents = list(nums - lead_nums)[:2], list(ents - lead_ents)[:2]
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


@app.post("/api/story/{sid}/split")
def split_story(sid: int, uid: int = Depends(current_user), db=Depends(get_db)):
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
        sims = [float(vecs[0] @ v) for v in vecs]
        cut = float(np.median(sims))
        groups = [g for g in ([i for i, s in enumerate(sims) if s >= cut], [i for i, s in enumerate(sims) if s < cut]) if g]
    new_ids = []
    for g in groups[1:]:
        nid = D.next_id(db, "stories")
        first = arts[g[0]]
        db.stories.insert_one({"_id": nid, "centroid": D.vec_to_bin(D.normalize(np.mean([vecs[i] for i in g], axis=0))),
                               "first_seen": first["published_at"], "last_updated": first["published_at"],
                               "source_count": 1, "topics": [], "places": [], "entities": [], "tier_hint": None,
                               "src_cities": [], "src_states": [], "lead_article_id": first["_id"], "thread_id": None,
                               "no_merge": True})
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
        "src_cities": sorted({a.get("src_city") for a in arts if a.get("src_city")}),
        "src_states": sorted({a.get("src_state") for a in arts if a.get("src_state")}),
    }})


@app.get("/api/thread/{tid}")
def thread_cards(tid: int, uid: int = Depends(current_user), db=Depends(get_db)):
    t = db.threads.find_one({"_id": tid})
    if not t:
        raise HTTPException(404, "thread not found")
    stories = list(db.stories.find({"thread_id": tid}).sort("last_updated", -1))
    states = {s["story_id"]: s for s in db.state.find({"uid": uid, "story_id": {"$in": [s["_id"] for s in stories]}})}
    sources = ranker.user_sources(db, uid)
    cards = []
    for s in stories:
        st = states.get(s["_id"]) or {}
        if st.get("status") in ("seen", "liked", "hidden", "fine"):
            continue
        arts = list(db.articles.find({"story_id": s["_id"]}, {"embedding": 0, "body": 0}))
        c = ranker.build_card(s, arts, sources, set(), st)
        if c:
            c["why"] = f"Part of {t['title']}"
            cards.append(c)
    return {"title": t["title"], "cards": cards}


@app.get("/api/article/{aid}/reader")
def reader(aid: int, uid: int = Depends(current_user), db=Depends(get_db)):
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
STATUS_FILTERS = {"liked": ["liked"], "fine": ["fine"], "seen": ["seen"], "less": ["hidden"]}


def _history_cards(db, uid: int, query: dict, *, q: str = "", topic: str = "", source: str = "",
                   local: bool = False, sort: str = "new", by_liked: bool = False, limit: int = 400):
    states = list(db.state.find({"uid": uid, **query}).sort("seen_at", -1).limit(2000))
    ids = [s["story_id"] for s in states]
    stories = {s["_id"]: s for s in db.stories.find({"_id": {"$in": ids}}, {"centroid": 0})}
    arts = defaultdict(list)
    for a in db.articles.find({"story_id": {"$in": ids}}, {"embedding": 0, "body": 0}):
        arts[a["story_id"]].append(a)
    sources = ranker.user_sources(db, uid)
    prefs = D.get_prefs(db, uid)
    out, ql = [], q.lower().strip()
    for st in states:
        sid = st["story_id"]
        s = stories.get(sid)
        if not s or not arts.get(sid):
            continue
        card = ranker.build_card(s, arts[sid], sources, set(), st)
        if not card:
            continue
        if ql and ql not in (card["title"] + " " + (card["summary"] or "") + " " + card["publisher"]).lower():
            continue
        if topic and topic not in card["topics"]:
            continue
        if source and source not in {a["publisher"] for a in arts[sid]}:
            continue
        tier = user_tier(s, prefs)
        if local and tier not in ("city", "state"):
            continue
        when = (st.get("liked_at") or st.get("saved_at")) if by_liked else st.get("seen_at")
        card["when"] = D.aware(when or st.get("seen_at") or s["last_updated"]).isoformat()
        card["status"] = st.get("status")
        card["tier"] = tier
        out.append(card)
    out.sort(key=lambda c: c["when"], reverse=(sort != "old"))
    return out[:limit]


@app.get("/api/seen")
def seen(q: str = "", topic: str = "", source: str = "", status: str = "", days: int = 0, local: bool = False,
         sort: str = "new", uid: int = Depends(current_user), db=Depends(get_db)):
    query: dict = {"seen_at": {"$ne": None}}
    if days > 0:
        query["seen_at"] = {"$gte": D.now() - timedelta(days=days)}
    if status in STATUS_FILTERS:
        query["status"] = {"$in": STATUS_FILTERS[status]}
    cards = _history_cards(db, uid, query, q=q, topic=topic, source=source, local=local, sort=sort)
    everything = _history_cards(db, uid, {"seen_at": {"$ne": None}}, limit=2000) if (topic or source) else cards
    counts = Counter(c["status"] for c in cards)
    return {"cards": cards, "topics": sorted({t for c in everything for t in c["topics"]}),
            "sources": sorted({c["publisher"] for c in everything}),
            "counts": {"liked": counts.get("liked", 0), "fine": counts.get("fine", 0),
                       "seen": counts.get("seen", 0), "less": counts.get("hidden", 0)}}


@app.get("/api/liked")
def liked(q: str = "", uid: int = Depends(current_user), db=Depends(get_db)):
    cards = _history_cards(db, uid, {"$or": [{"status": "liked"}, {"saved": True}]}, q=q, by_liked=True)
    return {"cards": cards}


# ------------------------------------------------------------------ controls
@app.get("/api/controls")
def controls(uid: int = Depends(current_user), db=Depends(get_db)):
    prefs = D.get_prefs(db, uid)
    rules = [{"id": r["_id"], "label": r["label"], "kind": r["kind"], "mode": r["mode"], "weight": r.get("weight", 1.0)}
             for r in db.rules.find({"uid": uid}, {"embedding": 0}).sort("created_at", 1)]
    week = D.now() - timedelta(days=7)
    counts = Counter(a.get("source_id") for a in db.articles.find({"published_at": {"$gte": D.now() - timedelta(days=3)}}, {"source_id": 1}))
    mine = {o["source_id"]: o for o in db.user_sources.find({"uid": uid})}
    srcs = []
    for s in db.sources.find({}).sort([("kind", 1), ("name", 1)]):
        # users see shared outlets, the feeds for their own city/state, and feeds they added
        if s["kind"] == "city" and s.get("city") != prefs["city"] and not (s.get("tier") == "state" and s.get("state") == prefs["state"]):
            continue
        if s["kind"] in ("google_search", "auto_interest"):
            continue
        if s["kind"] == "custom" and s.get("added_by") not in (uid, None):
            continue
        o = mine.get(s["_id"], {})
        last_new, created = D.aware(s.get("last_new_at")), D.aware(s.get("created_at"))
        dead = bool(s.get("last_polled")) and (last_new is None or last_new < week) and (created is None or created < week)
        srcs.append({"id": s["_id"], "name": s["name"], "url": s["url"], "kind": s["kind"],
                     "trust": o.get("trust", s.get("trust", 0.6)), "enabled": o.get("enabled", True),
                     "error": s.get("last_error"), "dead": dead, "articles_3d": counts.get(s["_id"], 0)})
    user = db.users.find_one({"_id": uid}, {"username": 1}) or {}
    week_ago = D.now() - timedelta(days=7)
    stats = {
        "read_week": db.state.count_documents({"uid": uid, "seen_at": {"$gte": week_ago}}),
        "liked_week": db.state.count_documents({"uid": uid, "status": "liked", "liked_at": {"$gte": week_ago}}),
        "saved": db.state.count_documents({"uid": uid, "saved": True}),
        "interests": db.clusters.count_documents({"uid": uid, "polarity": 1}),
    }
    return {"prefs": prefs, "rules": rules, "sources": srcs, "topics": list(TOPICS.keys()),
            "cities": [{"name": c, "state": v[0]} for c, v in CITIES.items()],
            "last_ingest": D.get_setting(db, "last_ingest"), "username": user.get("username"), "stats": stats}


class PrefsIn(BaseModel):
    city: str | None = None
    state: str | None = None
    tiers: dict[str, float] | None = None
    discovery: float | None = None
    paused_topics: list[str] | None = None
    onboarded: bool | None = None


@app.put("/api/prefs")
def put_prefs(body: PrefsIn, uid: int = Depends(current_user), db=Depends(get_db)):
    prefs = D.get_prefs(db, uid)
    data = body.model_dump(exclude_none=True)
    if "city" in data and data["city"] in CITIES and "state" not in data:
        data["state"] = CITIES[data["city"]][0]
    if "discovery" in data:
        data["discovery"] = max(0.0, min(0.5, data["discovery"]))
    if "tiers" in data:
        data["tiers"] = {k: max(0.0, min(1.0, float(v))) for k, v in {**prefs["tiers"], **data["tiers"]}.items()}
    prefs.update(data)
    D.set_prefs(db, uid, prefs)
    return prefs


class OnboardIn(BaseModel):
    city: str
    topics: list[str]


def _upsert_rule(db, uid: int, label: str, kind: str, mode: str) -> None:
    db.rules.update_one({"uid": uid, "kind": kind, "label": label},
                        {"$set": {"mode": mode, "weight": 1.0},
                         "$setOnInsert": {"_id": D.next_id(db, "rules"), "embedding": None, "created_at": D.now()}},
                        upsert=True)


@app.post("/api/onboard")
def onboard(body: OnboardIn, uid: int = Depends(current_user), db=Depends(get_db)):
    prefs = D.get_prefs(db, uid)
    prefs["city"] = body.city
    prefs["state"] = CITIES.get(body.city, (prefs["state"],))[0]
    prefs["onboarded"] = True
    D.set_prefs(db, uid, prefs)
    for t in body.topics:
        if t in TOPICS:
            _upsert_rule(db, uid, t, "topic", "follow")
    return {"ok": True}


class RuleIn(BaseModel):
    label: str
    kind: str = "keyword"   # topic | keyword | source | place
    mode: str = "follow"    # follow | mute | always | neutral


@app.post("/api/rules")
def add_rule(body: RuleIn, uid: int = Depends(current_user), db=Depends(get_db)):
    label = body.label.strip()[:80]
    if not label or body.kind not in ("topic", "keyword", "source", "place"):
        raise HTTPException(400, "bad rule")
    if body.mode == "neutral":
        db.rules.delete_one({"uid": uid, "kind": body.kind, "label": label})
        return {"ok": True}
    if body.mode not in ("follow", "mute", "always"):
        raise HTTPException(400, "bad mode")
    _upsert_rule(db, uid, label, body.kind, body.mode)
    return {"ok": True}


@app.delete("/api/rules/{rid}")
def delete_rule(rid: int, uid: int = Depends(current_user), db=Depends(get_db)):
    db.rules.delete_one({"_id": rid, "uid": uid})
    return {"ok": True}


class SourceIn(BaseModel):
    url: str
    name: str | None = None


@app.post("/api/sources")
def add_source(body: SourceIn, uid: int = Depends(current_user), db=Depends(get_db)):
    url = body.url.strip()
    if not re.match(r"^https?://", url):
        raise HTTPException(400, "URL must start with http(s)://")
    if db.sources.count_documents({"kind": "custom", "added_by": uid}) >= 30:
        raise HTTPException(400, "You can add up to 30 feeds")
    name = (body.name or "").strip() or re.sub(r"^https?://(www\.)?", "", url).split("/")[0]
    db.sources.update_one({"url": url}, {"$setOnInsert": {"_id": D.next_id(db, "sources"), "name": name, "kind": "custom",
                                                          "added_by": uid, "trust": 0.6, "tier": None, "enabled": True,
                                                          "created_at": D.now()}}, upsert=True)
    return {"ok": True}


class SourcePatch(BaseModel):
    trust: float | None = None
    enabled: bool | None = None


@app.patch("/api/sources/{source_id}")
def patch_source(source_id: int, body: SourcePatch, uid: int = Depends(current_user), db=Depends(get_db)):
    upd = body.model_dump(exclude_none=True)
    if "trust" in upd:
        upd["trust"] = max(0.0, min(1.0, upd["trust"]))
    db.user_sources.update_one({"uid": uid, "source_id": source_id}, {"$set": upd}, upsert=True)
    return {"ok": True}


@app.delete("/api/sources/{source_id}")
def delete_source(source_id: int, uid: int = Depends(current_user), db=Depends(get_db)):
    s = db.sources.find_one({"_id": source_id})
    if s and s.get("kind") == "custom" and s.get("added_by") == uid:
        db.sources.delete_one({"_id": source_id})
    else:   # shared feeds are only switched off for this user
        db.user_sources.update_one({"uid": uid, "source_id": source_id}, {"$set": {"enabled": False}}, upsert=True)
    return {"ok": True}


# ------------------------------------------------------------------ insights
@app.get("/api/insights")
def insights(uid: int = Depends(current_user), db=Depends(get_db)):
    now = D.now()
    out = []
    for c in db.clusters.find({"uid": uid}):
        w = P.effective_weight(c, now)
        out.append({"id": c["_id"], "label": P.cluster_label(c), "polarity": c["polarity"], "weight": round(w, 2),
                    "strength": round(w / (w + 2), 2), "signals": c.get("signals", 1),
                    "updated_at": D.aware(c["updated_at"]).isoformat()})
    out.sort(key=lambda c: (-c["polarity"], -c["weight"]))
    prefs = D.get_prefs(db, uid)
    return {"clusters": out, "paused_topics": prefs.get("paused_topics", []), "topics": list(TOPICS.keys()),
            "interactions": db.interactions.count_documents({"uid": uid, "undone": False})}


@app.delete("/api/insights/{cid}")
def forget_cluster(cid: int, uid: int = Depends(current_user), db=Depends(get_db)):
    db.clusters.delete_one({"_id": cid, "uid": uid})
    return {"ok": True}


class TopicIn(BaseModel):
    topic: str


@app.post("/api/insights/reset-topic")
def reset_topic(body: TopicIn, uid: int = Depends(current_user), db=Depends(get_db)):
    removed = 0
    for c in db.clusters.find({"uid": uid}):
        top = Counter(c.get("topics") or {}).most_common(1)
        if top and top[0][0] == body.topic:
            db.clusters.delete_one({"_id": c["_id"]})
            removed += 1
    return {"ok": True, "removed": removed}


@app.post("/api/insights/rebuild")
def rebuild(uid: int = Depends(current_user), db=Depends(get_db)):
    return {"ok": True, "replayed": P.rebuild(db, uid)}


@app.get("/api/session-summary")
def session_summary(since: str, uid: int = Depends(current_user), db=Depends(get_db)):
    try:
        t = datetime.fromisoformat(since.replace("Z", "+00:00"))
    except ValueError:
        raise HTTPException(400, "bad since")
    per_topic, n = Counter(), 0
    for it in db.interactions.find({"uid": uid, "created_at": {"$gte": t}, "undone": False}):
        n += 1
        w = it.get("weight") or 0
        if not w or not it.get("story_id") or it["story_id"] < 0:
            continue
        s = db.stories.find_one({"_id": it["story_id"]}, {"topics": 1})
        if s and s.get("topics"):
            per_topic[s["topics"][0]] += w
    more = [k for k, v in per_topic.most_common() if v > 0.5][:2]
    less = [k for k, v in sorted(per_topic.items(), key=lambda kv: kv[1]) if v < -0.5][:2]
    parts = (["more " + " and ".join(more)] if more else []) + (["less " + " and ".join(less)] if less else [])
    return {"note": ("Learned: " + ", ".join(parts)) if parts else None, "interactions": n}


@app.get("/api/app/latest")
def app_latest():
    """Where the Android app lives, for 'Share app' and download links."""
    repo = os.environ.get("PULSE_APP_REPO", "")
    return {"releases": f"https://github.com/{repo}/releases/latest" if repo else None}


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
