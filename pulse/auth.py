"""Accounts: username + password (scrypt), signed session tokens.

Web uses an HttpOnly cookie; the Android app sends the same token as a Bearer header.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import os
import re
import secrets
import time

from itsdangerous import BadSignature, TimestampSigner

from . import db as D

TOKEN_AGE = 60 * 60 * 24 * 365
USERNAME_RE = re.compile(r"^[a-z0-9_.]{3,24}$")
MAX_USERS = int(os.environ.get("PULSE_MAX_USERS", "1000"))
SIGNUPS_PER_HOUR = int(os.environ.get("PULSE_SIGNUPS_PER_HOUR", "30"))


def _secret() -> str:
    s = os.environ.get("PULSE_SECRET")
    if s:
        return s
    if os.environ.get("VERCEL") or os.environ.get("RENDER") or os.environ.get("RAILWAY_ENVIRONMENT"):
        raise RuntimeError("PULSE_SECRET must be set when hosted")
    return "pulse-local-dev-secret"


def _signer() -> TimestampSigner:
    return TimestampSigner(_secret(), salt="pulse-user")


def make_token(uid: int) -> str:
    return _signer().sign(str(uid).encode()).decode()


def read_token(token: str | None) -> int | None:
    if not token:
        return None
    try:
        raw = _signer().unsign(token, max_age=TOKEN_AGE)
        return int(raw.decode())
    except (BadSignature, ValueError):
        return None


def hash_password(password: str) -> str:
    salt = secrets.token_bytes(16)
    digest = hashlib.scrypt(password.encode(), salt=salt, n=2**14, r=8, p=1, dklen=32)
    return "scrypt$" + base64.b64encode(salt).decode() + "$" + base64.b64encode(digest).decode()


def check_password(password: str, stored: str) -> bool:
    try:
        _, salt_b64, digest_b64 = stored.split("$")
        salt = base64.b64decode(salt_b64)
        want = base64.b64decode(digest_b64)
        got = hashlib.scrypt(password.encode(), salt=salt, n=2**14, r=8, p=1, dklen=32)
        return hmac.compare_digest(got, want)
    except Exception:
        return False


def normalize_username(name: str) -> str:
    return name.strip().lower()


def validate_new(username: str, password: str) -> str | None:
    if not USERNAME_RE.match(username):
        return "Username must be 3–24 characters: letters, numbers, dots or underscores"
    if len(password) < 6:
        return "Password must be at least 6 characters"
    if len(password) > 200:
        return "Password is too long"
    return None


def signup_allowed(db) -> str | None:
    if db.users.count_documents({}) >= MAX_USERS:
        return "This Pulse server is full"
    window = int(time.time() // 3600)
    doc = db.settings.find_one_and_update(
        {"_id": f"signups:{window}"}, {"$inc": {"value": 1}}, upsert=True, return_document=True
    )
    if doc and doc.get("value", 0) > SIGNUPS_PER_HOUR:
        return "Too many new accounts right now. Try again in an hour."
    return None


def create_user(db, username: str, password: str) -> int:
    uid = D.next_id(db, "users")
    db.users.insert_one({"_id": uid, "username": username, "password": hash_password(password), "created_at": D.now()})
    return uid
