"""One-time migration from single-user Pulse to accounts. Safe to run more than once.

Creates the owner account (OWNER_USERNAME, password = the old PULSE_PASSWORD) and moves the
existing personal data (state, interactions, clusters, rules, prefs, source trust) onto it.

Usage:  OWNER_USERNAME=kathin PULSE_PASSWORD=... python -m worker.migrate_users
"""
from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from pulse import auth as A  # noqa: E402
from pulse import db as D  # noqa: E402


def main() -> None:
    db = D.get_db()
    username = A.normalize_username(os.environ.get("OWNER_USERNAME", "owner"))
    owner = db.users.find_one({"username": username})
    if owner:
        uid = owner["_id"]
        print(f"owner '{username}' exists (uid {uid})")
    else:
        password = os.environ.get("PULSE_PASSWORD")
        if not password:
            raise SystemExit("set PULSE_PASSWORD to the existing password for the owner account")
        uid = A.create_user(db, username, password)
        print(f"created owner '{username}' (uid {uid})")

    # prefs
    old = D.get_setting(db, "prefs")
    if old is not None and D.get_setting(db, f"prefs:{uid}") is None:
        D.set_prefs(db, uid, old)
        print("moved prefs")
    prefs = D.get_prefs(db, uid)

    # rules: the old unique index was (kind, label); the new one includes uid
    for name in list(db.rules.index_information()):
        if name == "kind_1_label_1":
            db.rules.drop_index(name)
            print("dropped old rules index")
    r = db.rules.update_many({"uid": {"$exists": False}}, {"$set": {"uid": uid}})
    print("rules:", r.modified_count)

    r = db.clusters.update_many({"uid": {"$exists": False}}, {"$set": {"uid": uid}})
    print("clusters:", r.modified_count)
    r = db.interactions.update_many({"uid": {"$exists": False}}, {"$set": {"uid": uid}})
    print("interactions:", r.modified_count)

    # state: _id was the story id; now "<uid>:<story_id>" with uid + story_id fields
    moved = 0
    pending = {s["_id"]: s.get("pending_update_article_id") for s in db.stories.find(
        {"pending_update_article_id": {"$ne": None}}, {"pending_update_article_id": 1})}
    for st in list(db.state.find({"uid": {"$exists": False}})):
        sid = st["_id"]
        doc = {k: v for k, v in st.items() if k != "_id"}
        doc.update({"_id": D.state_id(uid, sid), "uid": uid, "story_id": sid,
                    "pending_update_article_id": pending.get(sid)})
        db.state.replace_one({"_id": doc["_id"]}, doc, upsert=True)
        db.state.delete_one({"_id": sid})
        moved += 1
    print("state:", moved)
    db.stories.update_many({}, {"$unset": {"pending_update_article_id": ""}})

    # source trust / learned bias become this user's overrides
    n = 0
    for s in db.sources.find({"learned_bias": {"$exists": True}}):
        if s.get("learned_bias"):
            db.user_sources.update_one({"uid": uid, "source_id": s["_id"]}, {"$set": {"bias": s["learned_bias"]}}, upsert=True)
            n += 1
    db.sources.update_many({}, {"$unset": {"learned_bias": ""}})
    print("source overrides:", n)

    # stories: per-user tiers need hints instead of the old single-user tier
    for tier, upd in (("city", {"src_cities": [prefs["city"]], "src_states": [prefs["state"]]}),
                      ("state", {"src_cities": [], "src_states": [prefs["state"]]}),
                      ("india", {"tier_hint": "india"}), ("world", {"tier_hint": "world"})):
        db.stories.update_many({"tier": tier}, {"$set": upd})
    db.stories.update_many({}, {"$unset": {"tier": ""}})
    db.articles.update_many({}, {"$unset": {"tier": ""}})

    D.ensure_indexes(db)
    print("done")


if __name__ == "__main__":
    main()
