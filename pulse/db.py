"""MongoDB helper shared by the API (Vercel) and the ingest worker (GitHub Actions).

Embeddings are stored as raw float32 bytes and compared in numpy, which is fast
enough for one reader and works on Atlas's free tier without extra indexes.
"""
from __future__ import annotations

import os
from datetime import datetime, timezone

import numpy as np
from bson.binary import Binary
from pymongo import ASCENDING, DESCENDING, MongoClient, ReturnDocument

DIM = 384
_client: MongoClient | None = None


def now() -> datetime:
    return datetime.now(timezone.utc)


def aware(dt: datetime | None) -> datetime | None:
    """Mongo returns naive UTC datetimes; make them timezone-aware."""
    if dt is None:
        return None
    return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)


def get_db():
    global _client
    uri = os.environ.get("MONGODB_URI")
    if not uri:
        raise RuntimeError("MONGODB_URI is not set")
    if _client is None:
        _client = MongoClient(uri, tz_aware=True, serverSelectionTimeoutMS=8000, maxPoolSize=5)
    return _client[os.environ.get("MONGODB_DB", "pulse")]


def ensure_indexes(db) -> None:
    # shared news
    db.sources.create_index("url", unique=True)
    db.articles.create_index("url", unique=True)
    db.articles.create_index("story_id")
    db.articles.create_index([("published_at", DESCENDING)])
    db.articles.create_index("title_norm")
    db.stories.create_index([("last_updated", DESCENDING)])
    db.stories.create_index("thread_id")
    # per-user data (everything personal carries a uid)
    db.users.create_index("username", unique=True)
    db.state.create_index([("uid", ASCENDING), ("story_id", ASCENDING)], unique=True)
    db.state.create_index([("uid", ASCENDING), ("seen_at", DESCENDING)])
    db.state.create_index("story_id")
    db.interactions.create_index([("uid", ASCENDING), ("created_at", DESCENDING)])
    db.rules.create_index([("uid", ASCENDING), ("kind", ASCENDING), ("label", ASCENDING)], unique=True)
    db.clusters.create_index("uid")
    db.user_sources.create_index([("uid", ASCENDING), ("source_id", ASCENDING)], unique=True)


def next_id(db, name: str) -> int:
    doc = db.counters.find_one_and_update(
        {"_id": name}, {"$inc": {"seq": 1}}, upsert=True, return_document=ReturnDocument.AFTER
    )
    return int(doc["seq"])


def next_ids(db, name: str, n: int) -> list[int]:
    """Reserve n sequential ids in one round trip."""
    if n <= 0:
        return []
    doc = db.counters.find_one_and_update(
        {"_id": name}, {"$inc": {"seq": n}}, upsert=True, return_document=ReturnDocument.AFTER
    )
    end = int(doc["seq"])
    return list(range(end - n + 1, end + 1))


def vec_to_bin(v) -> Binary | None:
    if v is None:
        return None
    return Binary(np.asarray(v, dtype=np.float32).tobytes())


def bin_to_vec(b) -> np.ndarray | None:
    if b is None:
        return None
    return np.frombuffer(bytes(b), dtype=np.float32)


def normalize(v: np.ndarray) -> np.ndarray:
    n = float(np.linalg.norm(v))
    return v / n if n > 0 else v


DEFAULT_PREFS = {
    "onboarded": False,
    "city": "Bengaluru",
    "state": "Karnataka",
    "tiers": {"city": 0.7, "state": 0.5, "india": 0.6, "world": 0.4},
    "discovery": 0.10,
    "paused_topics": [],
}


def get_setting(db, key, default=None):
    doc = db.settings.find_one({"_id": key})
    return doc["value"] if doc else default


def set_setting(db, key, value) -> None:
    db.settings.update_one({"_id": key}, {"$set": {"value": value}}, upsert=True)


def get_prefs(db, uid: int) -> dict:
    stored = get_setting(db, f"prefs:{uid}", {}) or {}
    prefs = {**DEFAULT_PREFS, **stored}
    prefs["tiers"] = {**DEFAULT_PREFS["tiers"], **(stored.get("tiers") or {})}
    return prefs


def set_prefs(db, uid: int, prefs: dict) -> None:
    set_setting(db, f"prefs:{uid}", prefs)


def state_id(uid: int, story_id: int) -> str:
    return f"{uid}:{story_id}"
