"""Learned interest profile: per-user interest clusters nudged by every interaction."""
from __future__ import annotations

from collections import Counter

import numpy as np

from . import db as D

HALF_LIFE_DAYS = 30
JOIN_SIM = 0.55           # a signal joins the nearest cluster if at least this similar
NEW_CLUSTER_FACTOR = 0.6  # new clusters start weaker, so one like doesn't take over

SIGNAL_WEIGHTS = {
    "right": 1.0,
    "read": 0.8,        # opened and read > 30 s
    "open": 0.4,        # opened, short read
    "tap": 0.3,
    "up": 0.15,         # "fine": acknowledged, keep this kind of story steady
    "left_paused": 0.1,
    "left_quick": -0.2,
    "down": -1.0,
}


def signal_weight(action: str, dwell_ms: int | None) -> float:
    if action == "left":
        if dwell_ms is not None and dwell_ms < 1000:
            return SIGNAL_WEIGHTS["left_quick"]
        if dwell_ms is not None and dwell_ms > 4000:
            return SIGNAL_WEIGHTS["left_paused"]
        return 0.0
    if action == "read":
        return SIGNAL_WEIGHTS["read"] if (dwell_ms or 0) >= 30000 else SIGNAL_WEIGHTS["open"] * min(1.0, (dwell_ms or 0) / 30000 + 0.25)
    return SIGNAL_WEIGHTS.get(action, 0.0)


def effective_weight(c: dict, now=None) -> float:
    now = now or D.now()
    age = (now - D.aware(c["updated_at"])).total_seconds() / 86400
    return float(c["weight"]) * 0.5 ** (max(age, 0) / HALF_LIFE_DAYS)


def apply_signal(db, uid: int, story: dict, weight: float, at=None, prefs: dict | None = None) -> None:
    """Move this user's nearest same-polarity cluster towards the story, or start a new one."""
    if not weight or story.get("centroid") is None:
        return
    prefs = prefs or D.get_prefs(db, uid)
    paused = set(prefs.get("paused_topics") or [])
    topics = story.get("topics") or []
    if topics and topics[0] in paused:
        return
    at = at or D.now()
    v = D.bin_to_vec(story["centroid"])
    polarity = 1 if weight > 0 else -1
    mag = abs(weight)
    clusters = list(db.clusters.find({"uid": uid, "polarity": polarity}))
    best, best_sim = None, -1.0
    for c in clusters:
        s = float(D.bin_to_vec(c["centroid"]) @ v)
        if s > best_sim:
            best, best_sim = c, s
    names = list((story.get("entities") or [])[:6])
    if best is not None and best_sim >= JOIN_SIM:
        lr = min(0.35, 0.15 * mag + 0.05)
        c_vec = D.normalize((1 - lr) * D.bin_to_vec(best["centroid"]) + lr * v)
        t = Counter(best.get("topics") or {})
        t.update(topics[:2])
        terms = Counter(best.get("terms") or {})
        terms.update(names)
        db.clusters.update_one({"_id": best["_id"]}, {"$set": {
            "centroid": D.vec_to_bin(c_vec),
            "weight": min(effective_weight(best, at) + mag, 25.0),
            "signals": best.get("signals", 1) + 1,
            "topics": dict(t.most_common(8)),
            "terms": dict(terms.most_common(20)),
            "updated_at": at,
        }})
    else:
        db.clusters.insert_one({
            "_id": D.next_id(db, "clusters"), "uid": uid, "centroid": D.vec_to_bin(v),
            "weight": mag * NEW_CLUSTER_FACTOR, "polarity": polarity, "signals": 1,
            "topics": dict(Counter(topics[:2])), "terms": dict(Counter(names)), "updated_at": at,
        })
    # A positive signal softens a nearby negative cluster (and vice versa), so mistakes heal.
    for c in db.clusters.find({"uid": uid, "polarity": -polarity}):
        if float(D.bin_to_vec(c["centroid"]) @ v) >= 0.7:
            db.clusters.update_one({"_id": c["_id"]}, {"$set": {"weight": max(0.0, effective_weight(c, at) - 0.3 * mag), "updated_at": at}})
    db.clusters.delete_many({"uid": uid, "weight": {"$lt": 0.05}})


def nudge_source(db, uid: int, story: dict, weight: float) -> None:
    """'Less like this' also lowers this user's view of the lead outlet a little."""
    if weight >= 0:
        return
    lead = db.articles.find_one({"_id": story.get("lead_article_id")}, {"source_id": 1})
    if not lead or not lead.get("source_id"):
        return
    key = {"uid": uid, "source_id": lead["source_id"]}
    doc = db.user_sources.find_one(key) or {}
    bias = max(-0.3, doc.get("bias", 0.0) - 0.03)
    db.user_sources.update_one(key, {"$set": {"bias": bias}}, upsert=True)


def cluster_label(c: dict) -> str:
    terms = [t for t, _ in Counter(c.get("terms") or {}).most_common(3)]
    topics = [t for t, _ in Counter(c.get("topics") or {}).most_common(1)]
    parts = topics + [t for t in terms if t not in topics][:2]
    return " · ".join(parts) if parts else "Unlabelled interest"


def rebuild(db, uid: int) -> int:
    """Replay this user's interaction log to rebuild their clusters from scratch."""
    db.clusters.delete_many({"uid": uid})
    prefs = D.get_prefs(db, uid)
    n = 0
    stories: dict[int, dict | None] = {}
    for it in db.interactions.find({"uid": uid, "undone": False, "weight": {"$ne": 0}}).sort("created_at", 1):
        w = it.get("weight") or 0
        for sid in it.get("meta", {}).get("story_ids") or [it.get("story_id")]:
            if not w or not sid or sid < 0:
                continue
            if sid not in stories:
                stories[sid] = db.stories.find_one({"_id": sid}, {"centroid": 1, "topics": 1, "entities": 1})
            if stories[sid]:
                apply_signal(db, uid, stories[sid], w, at=D.aware(it["created_at"]), prefs=prefs)
                n += 1
    return n


def interest_score(story_vec: np.ndarray, pos: list[tuple[np.ndarray, float]], neg: list[tuple[np.ndarray, float]]) -> float:
    def scaled(sim):
        return min(1.0, max(0.0, (sim - 0.25) / 0.45))

    best_pos = 0.0
    for c, w in pos:
        best_pos = max(best_pos, scaled(float(c @ story_vec)) * (w / (w + 2.0)))
    best_neg = 0.0
    for c, w in neg:
        best_neg = max(best_neg, scaled(float(c @ story_vec)) * (w / (w + 1.5)))
    return best_pos - best_neg
