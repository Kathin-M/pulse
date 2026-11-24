"""Feed ranking: explicit rules first, learned interests fill the rest.

score = 0.40 I + 0.20 R + 0.15 M + 0.15 F + 0.10 S - P
"""
from __future__ import annotations

import math
import re
from collections import Counter
from datetime import timedelta

import numpy as np

from . import db as D
from .profile import cluster_label, effective_weight, interest_score
from .taxonomy import CITIES, EVERGREEN_TOPICS

WINDOW_H = 72
MIN_SCORE = 0.20
CARD_FIELDS = {"embedding": 0, "body": 0}


def _rules(db):
    rules = list(db.rules.find({}, {"embedding": 0}))
    out = {"mute": [], "follow": [], "always": []}
    for r in rules:
        out.setdefault(r["mode"], []).append(r)
    return out


def _matches(rule: dict, text: str, topics: list[str], places: list[str], publishers: set[str]) -> bool:
    label = rule["label"]
    kind = rule["kind"]
    if kind == "topic":
        return label in topics
    if kind == "source":
        return label in publishers or any(p.startswith(label) for p in publishers)
    if kind == "place":
        if label in places:
            return True
        aliases = CITIES.get(label, (None, [], None))[1]
        return any(a in places for a in aliases) or re.search(rf"(?<!\w){re.escape(label)}(?!\w)", text, re.I) is not None
    return re.search(rf"(?<!\w){re.escape(label)}(?!\w)", text, re.I) is not None


def _layout(w: int | None) -> str:
    if not w:
        return "text"
    if w >= 1000:
        return "full"
    if w >= 500:
        return "photo"
    return "text"


def load_candidates(db, include_seen_updates=True):
    now = D.now()
    since = now - timedelta(hours=WINDOW_H)
    stories = {s["_id"]: s for s in db.stories.find({"last_updated": {"$gte": since}})}
    states = {s["_id"]: s for s in db.state.find({"_id": {"$in": list(stories)}})}
    arts_by_story: dict[int, list[dict]] = {}
    for a in db.articles.find({"story_id": {"$in": list(stories)}}, {"embedding": 0, "body": 0}):
        arts_by_story.setdefault(a["story_id"], []).append(a)
    return now, stories, states, arts_by_story


def build_card(story: dict, arts: list[dict], sources: dict, muted_publishers: set[str], state: dict | None = None) -> dict | None:
    visible = [a for a in arts if a.get("publisher") not in muted_publishers]
    if not visible:
        return None
    reports = [a for a in visible if a.get("kind") in ("report", "video")] or visible
    lead = next((a for a in reports if a["_id"] == story.get("lead_article_id")), None) or max(
        reports, key=lambda a: (sources.get(a.get("source_id"), {}).get("trust", 0.6), len(a.get("summary") or "")))
    imgs = [a for a in visible if a.get("image_w")]
    best_img = None
    if lead.get("image_w") and lead["image_w"] >= 1000:
        best_img = lead
    elif imgs:
        best_img = max(imgs, key=lambda a: a["image_w"])
    summary = lead.get("summary") or next((a["summary"] for a in reports if a.get("summary")), "")
    publishers = sorted({a["publisher"] for a in reports})
    state = state or {}
    return {
        "id": story["_id"],
        "type": "story",
        "title": lead["title"],
        "summary": summary,
        "url": lead.get("url_original") or lead["url"],
        "article_id": lead["_id"],
        "publisher": lead["publisher"],
        "source_count": len(publishers),
        "first_seen": D.aware(story["first_seen"]).isoformat(),
        "last_updated": D.aware(story["last_updated"]).isoformat(),
        "topics": story.get("topics") or [],
        "places": story.get("places") or [],
        "tier": story.get("tier"),
        "image": best_img["image"] if best_img else None,
        "image_w": best_img["image_w"] if best_img else None,
        "image_h": best_img.get("image_h") if best_img else None,
        "lqip": best_img.get("image_lqip") if best_img else None,
        "layout": _layout(best_img["image_w"] if best_img else None),
        "has_body": False,
        "thread_id": story.get("thread_id"),
        "liked": state.get("status") == "liked",
        "saved": bool(state.get("saved")),
        "followed": bool(state.get("followed")),
        "badge": None,
        "why": "",
    }


def rank(db, mode: str = "foryou", limit: int = 60, exclude: set[int] | None = None) -> dict:
    exclude = exclude or set()
    prefs = D.get_prefs(db)
    rules = _rules(db)
    sources = {s["_id"]: s for s in db.sources.find({}, {"trust": 1, "learned_bias": 1, "name": 1})}
    muted_publishers = {r["label"] for r in rules["mute"] if r["kind"] == "source"}
    now, stories, states, arts_by_story = load_candidates(db)

    clusters = list(db.clusters.find({}))
    pos = [(D.bin_to_vec(c["centroid"]), effective_weight(c, now)) for c in clusters if c["polarity"] > 0]
    neg = [(D.bin_to_vec(c["centroid"]), effective_weight(c, now)) for c in clusters if c["polarity"] < 0]
    labels = {i: cluster_label(c) for i, c in enumerate(c for c in clusters if c["polarity"] > 0)}

    tiers = prefs["tiers"]
    city = prefs["city"]
    scored = []
    for sid, story in stories.items():
        if sid in exclude:
            continue
        st = states.get(sid) or {}
        status = st.get("status", "unseen")
        arts = arts_by_story.get(sid) or []
        if not arts or status == "hidden":
            continue
        is_update = False
        if status in ("seen", "liked"):
            pend = story.get("pending_update_article_id")
            last = D.aware(st.get("last_update_shown"))
            if not pend or pend in (st.get("seen_article_ids") or []):
                continue
            if last and now - last < timedelta(hours=12):
                continue
            is_update = True
        card = build_card(story, arts, sources, muted_publishers, st)
        if not card:
            continue
        publishers = {a["publisher"] for a in arts}
        text = " ".join([card["title"], card["summary"] or "", " ".join(story.get("entities") or [])])
        topics, places = story.get("topics") or [], story.get("places") or []
        if any(_matches(r, text, topics, places, publishers) for r in rules["mute"] if r["kind"] != "source"):
            continue
        always = [r for r in rules["always"] if _matches(r, text, topics, places, publishers)]
        follows = [r for r in rules["follow"] if _matches(r, text, topics, places, publishers)]
        # name the follow that best describes the story (earliest topic first)
        follows.sort(key=lambda r: topics.index(r["label"]) if r["kind"] == "topic" and r["label"] in topics else (-1 if r["kind"] != "topic" else 99))

        vec = D.bin_to_vec(story["centroid"])
        I = interest_score(vec, pos, neg) if (pos or neg) else 0.0
        best_cluster = None
        if pos:
            sims = [float(c @ vec) * (w / (w + 2)) for c, w in pos]
            best_cluster = int(np.argmax(sims))
        follow_score = min(1.0, sum(r.get("weight", 1.0) for r in follows) * 0.6)
        tier = story.get("tier") or "india"
        tier_val = float(tiers.get(tier, 0.5))
        R = min(1.0, 0.65 * follow_score + 0.5 * tier_val)
        n = card["source_count"]
        M = min(1.0, math.log1p(n) / math.log1p(12))
        age_h = (now - D.aware(story["last_updated"])).total_seconds() / 3600 * 0.5 + \
                (now - D.aware(story["first_seen"])).total_seconds() / 3600 * 0.5
        half = 24 if (topics and topics[0] in EVERGREEN_TOPICS) else 12
        F = 0.5 ** (max(age_h, 0) / half)
        trusts = [sources.get(a.get("source_id"), {}).get("trust", 0.6) for a in arts]
        lead_src = sources.get(next((a.get("source_id") for a in arts if a["_id"] == card["article_id"]), None), {})
        S = max(trusts) + lead_src.get("learned_bias", 0.0)
        score = 0.40 * I + 0.20 * R + 0.15 * M + 0.15 * F + 0.10 * S
        if always:
            score += 1.0
        if is_update:
            score += 0.15 + (0.25 if st.get("followed") else 0)
            upd_art = next((a for a in arts if a["_id"] == story["pending_update_article_id"]), None)
            card["type"] = "update"
            card["badge"] = "Update"
            if upd_art:
                card["update_title"] = upd_art["title"]
                card["update_publisher"] = upd_art["publisher"]
                card["update_article_id"] = upd_art["_id"]
        if not is_update and n >= 6 and tier in ("city", "state", "india") and age_h < 8:
            card["badge"] = "Breaking"

        # Why line: the biggest contributor wins.
        if always:
            why = f"Always show: {always[0]['label']}"
        elif is_update:
            why = "Since you saw this" + (" · you follow this story" if st.get("followed") else "")
        elif follows:
            why = f"You follow {follows[0]['label']}"
        elif tier == "city" and tier_val >= 0.5:
            why = f"You follow {city}"
        elif I >= 0.25 and best_cluster is not None:
            why = f"Like stories you liked: {labels.get(best_cluster, 'your interests')}"
        elif M >= 0.6:
            why = "Big story across outlets"
        elif tier == "state":
            why = f"News from {prefs['state']}"
        else:
            why = "Fresh and widely reported" if F > 0.6 else "Fills a gap in your feed"
        if n >= 3 and "outlets" not in why:
            why += f" · {n} outlets"
        card["why"] = why

        follow_hit = bool(follows or always or st.get("followed"))
        scored.append({"card": card, "score": score, "I": I, "M": M, "F": F, "vec": vec,
                       "topic": topics[0] if topics else None, "tier": tier, "follow": follow_hit,
                       "thread": story.get("thread_id"), "pinned": bool(always)})

    if mode == "local":
        scored = [s for s in scored if s["tier"] in ("city", "state")]
    elif mode == "following":
        scored = [s for s in scored if s["follow"]]

    scored.sort(key=lambda s: -s["score"])
    good = [s for s in scored if s["score"] >= MIN_SCORE or s["pinned"] or s["card"]["source_count"] >= 4]

    # ---- threads: at most 2 standalone stories per thread per day, the rest wait inside a thread card
    shown_today = Counter()
    day_start = now - timedelta(hours=24)
    for st in states.values():
        if st.get("seen_at") and D.aware(st["seen_at"]) >= day_start:
            s = stories.get(st["_id"])
            if s and s.get("thread_id"):
                shown_today[s["thread_id"]] += 1
    by_thread: dict[int, list[dict]] = {}
    for s in good:
        if s["thread"] and s["card"]["type"] == "story":
            by_thread.setdefault(s["thread"], []).append(s)
    thread_cards = []
    held = set()
    if mode == "foryou":
        titles = {t["_id"]: t["title"] for t in db.threads.find({"_id": {"$in": list(by_thread)}})}
        for tid, members in by_thread.items():
            if len(members) < 3:
                continue
            allowed = max(0, 2 - shown_today[tid])
            rest = members[allowed:]
            if len(rest) < 2:
                continue
            for m in rest:
                held.add(m["card"]["id"])
            top = rest[0]
            tcard = dict(top["card"])
            tcard.update({
                "id": -tid, "type": "thread", "badge": "Thread", "thread_id": tid,
                "thread_title": titles.get(tid, top["topic"] or "Developing"),
                "thread_count": len(members), "story_ids": [m["card"]["id"] for m in members],
                "headlines": [m["card"]["title"] for m in members[:4]],
                "why": f"{len(members)} stories on one big event",
            })
            thread_cards.append({**top, "card": tcard, "score": top["score"] + 0.05, "thread": None})
    good = [s for s in good if s["card"]["id"] not in held] + thread_cards
    good.sort(key=lambda s: -s["score"])

    # ---- exploration slots
    discovery = float(prefs.get("discovery", 0.1))
    target = min(limit, len(good))
    explore = []
    if mode == "foryou" and discovery > 0 and pos:
        pool = [s for s in scored if s["I"] <= 0.08 and s["card"]["id"] not in held and s["card"]["type"] == "story"]
        pool.sort(key=lambda s: -(0.6 * s["M"] + 0.4 * s["F"]))
        explore = pool[: max(1, int(round(target * discovery)))]
    explore_ids = {s["card"]["id"] for s in explore}
    main = [s for s in good if s["card"]["id"] not in explore_ids]

    # ---- local guarantee
    local_share = 0.3 * (tiers.get("city", 0.5) + tiers.get("state", 0.5)) / 2
    head = main[:target]
    min_local = int(round(len(head) * local_share)) if mode == "foryou" else 0
    have_local = sum(1 for s in head if s["tier"] in ("city", "state"))
    if have_local < min_local:
        extra = [s for s in main[target:] if s["tier"] in ("city", "state")][: min_local - have_local]
        if extra:
            keep_n = len(head) - len(extra)
            non_local_drop = [s for s in reversed(head) if s["tier"] not in ("city", "state")][: len(extra)]
            drop_ids = {id(s) for s in non_local_drop}
            head = [s for s in head if id(s) not in drop_ids][:keep_n] + extra
            head.sort(key=lambda s: -s["score"])

    # ---- variety re-rank: no more than two in a row from one topic; avoid near-duplicates back to back
    ordered: list[dict] = []
    pending = head[:]
    while pending:
        pick = None
        for cand in pending[:8]:
            last2 = [o["topic"] for o in ordered[-2:]]
            if len(last2) == 2 and last2[0] == last2[1] == cand["topic"] and cand["topic"]:
                continue
            if any(float(cand["vec"] @ o["vec"]) > 0.8 for o in ordered[-3:]):
                continue
            pick = cand
            break
        pick = pick or pending[0]
        pending.remove(pick)
        ordered.append(pick)

    # interleave exploration cards evenly
    if explore:
        step = max(3, int(1 / max(discovery, 0.05)))
        for k, s in enumerate(explore):
            s["card"]["badge"] = "Something new"
            s["card"]["type"] = "explore" if s["card"]["type"] == "story" else s["card"]["type"]
            s["card"]["why"] = "Something new: testing a topic outside your usual interests"
            pos_i = min(len(ordered), (k + 1) * step)
            ordered.insert(pos_i, s)

    cards = [s["card"] for s in ordered[:limit]]
    return {"cards": cards, "total": len(cards), "mode": mode}
