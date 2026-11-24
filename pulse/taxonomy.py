"""Topics, places and light-weight text analysis. Pure Python, no heavy deps."""
from __future__ import annotations

import re
import unicodedata

# ---------------------------------------------------------------- topics
# topic -> keyword patterns (matched case-insensitively on word boundaries)
TOPICS: dict[str, list[str]] = {
    "Politics": ["election", "elections", "bjp", "congress", "parliament", "lok sabha", "rajya sabha", "minister",
                 "chief minister", "assembly", "mla", "mp", "opposition", "poll", "polls", "modi", "rahul gandhi",
                 "cabinet", "governor", "party", "manifesto", "aap", "dmk", "aiadmk", "tvk", "by-election"],
    "World": ["united nations", "un ", "ukraine", "russia", "china", "gaza", "israel", "iran", "pakistan",
              "white house", "trump", "nato", "eu ", "european union", "beijing", "kremlin", "summit"],
    "Business": ["company", "companies", "revenue", "profit", "quarter", "q1", "q2", "q3", "q4", "ipo", "merger",
                 "acquisition", "ceo", "earnings", "gdp", "inflation", "rbi", "economy", "tariff", "exports", "trade deal"],
    "Markets": ["sensex", "nifty", "stock", "stocks", "shares", "market cap", "bse", "nse", "rupee", "bond yield",
                "gold price", "crude", "mutual fund", "sebi"],
    "Startups": ["startup", "start-up", "funding round", "series a", "series b", "seed round", "unicorn", "venture",
                 "founder", "valuation", "zepto", "swiggy", "ola", "flipkart", "razorpay"],
    "Technology": ["tech", "software", "app", "smartphone", "iphone", "android", "google", "apple", "microsoft",
                   "meta", "chip", "semiconductor", "cyber", "data breach", "5g", "gadget", "laptop", "samsung"],
    "AI": ["ai", "artificial intelligence", "openai", "chatgpt", "llm", "gemini", "anthropic", "claude",
           "machine learning", "deepfake", "nvidia"],
    "Science": ["scientists", "study finds", "research", "researchers", "physics", "biology", "fossil", "climate study",
                "discovery", "telescope", "quantum"],
    "Space": ["isro", "nasa", "spacex", "rocket", "satellite", "gaganyaan", "chandrayaan", "orbit", "launch vehicle", "moon", "mars"],
    "Health": ["health", "hospital", "doctor", "disease", "virus", "vaccine", "covid", "dengue", "cancer", "medical",
               "who ", "patients", "outbreak", "aiims", "mental health"],
    "Environment": ["climate", "pollution", "aqi", "air quality", "heatwave", "flood", "floods", "cyclone", "monsoon",
                    "rain", "rainfall", "forest", "wildlife", "tiger", "elephant", "emissions", "imd"],
    "Cricket": ["cricket", "ipl", "bcci", "test match", "odi", "t20", "kohli", "rohit sharma", "wicket", "innings", "rcb", "csk"],
    "Football": ["football", "fifa", "premier league", "isl", "messi", "ronaldo", "champions league", "la liga"],
    "Formula 1": ["formula 1", "formula one", "f1", "grand prix", "verstappen", "hamilton", "ferrari", "mclaren"],
    "Sports": ["olympic", "olympics", "tennis", "badminton", "hockey", "chess", "athletics", "medal", "tournament",
               "championship", "kabaddi", "wrestling"],
    "Entertainment": ["film", "movie", "box office", "trailer", "actor", "actress", "director", "ott", "netflix",
                      "prime video", "series", "music", "album", "concert", "kollywood", "tollywood", "bollywood"],
    "Celebrity": ["celebrity", "wedding", "dating", "spotted", "red carpet", "gossip", "instagram post"],
    "Crime": ["police", "arrested", "arrest", "murder", "fir", "court", "accused", "probe", "fraud", "scam",
              "theft", "killed", "assault", "cbi", "ed raid", "custody"],
    "Law": ["supreme court", "high court", "verdict", "judgment", "bail", "petition", "bench", "cji", "law", "bill passed"],
    "Transport": ["metro", "railway", "train", "airport", "flight", "airline", "traffic", "bus", "highway",
                  "expressway", "road", "bmtc", "namma metro", "vande bharat", "indigo", "air india"],
    "Auto & EV": ["ev", "electric vehicle", "car", "cars", "suv", "tata motors", "mahindra", "maruti", "tesla",
                  "two-wheeler", "scooter", "battery", "charging"],
    "Civic": ["civic", "municipal", "bbmp", "gba", "corporation", "water supply", "garbage", "potholes", "power cut",
              "property tax", "lake", "drain", "ward"],
    "Education": ["school", "schools", "university", "college", "exam", "exams", "neet", "jee", "cbse", "students", "ugc", "iit"],
    "Defence": ["army", "navy", "air force", "iaf", "military", "defence", "border", "missile", "drdo", "soldiers"],
    "Gaming": ["gaming", "video game", "playstation", "xbox", "nintendo", "esports", "steam"],
    "Lifestyle": ["recipe", "food", "restaurant", "travel", "fashion", "fitness", "wellness", "lifestyle"],
}

SECTION_TOPICS = {  # Google News sections -> our topic
    "WORLD": "World", "NATION": "Politics", "BUSINESS": "Business", "TECHNOLOGY": "Technology",
    "SCIENCE": "Science", "HEALTH": "Health", "SPORTS": "Sports", "ENTERTAINMENT": "Entertainment",
}

EVERGREEN_TOPICS = {"Science", "Space", "Health", "Environment", "Lifestyle"}

_TOPIC_RES = {
    t: re.compile(r"(?<![\w-])(" + "|".join(re.escape(k.strip()) for k in kws) + r")(?![\w-])", re.I)
    for t, kws in TOPICS.items()
}


def tag_topics(text: str, hint: str | None = None, limit: int = 3) -> list[str]:
    scores: dict[str, int] = {}
    for topic, rx in _TOPIC_RES.items():
        hits = len(rx.findall(text))
        if hits:
            scores[topic] = hits
    if hint:
        scores[hint] = scores.get(hint, 0) + 2
    ranked = sorted(scores, key=lambda t: -scores[t])
    return ranked[:limit]


# ---------------------------------------------------------------- places
STATES = [
    "Andhra Pradesh", "Arunachal Pradesh", "Assam", "Bihar", "Chhattisgarh", "Goa", "Gujarat", "Haryana",
    "Himachal Pradesh", "Jharkhand", "Karnataka", "Kerala", "Madhya Pradesh", "Maharashtra", "Manipur",
    "Meghalaya", "Mizoram", "Nagaland", "Odisha", "Punjab", "Rajasthan", "Sikkim", "Tamil Nadu", "Telangana",
    "Tripura", "Uttar Pradesh", "Uttarakhand", "West Bengal", "Delhi", "Jammu and Kashmir", "Ladakh", "Puducherry",
    "Chandigarh",
]

# city -> (state, aliases, The Hindu city slug or None)
CITIES: dict[str, tuple[str, list[str], str | None]] = {
    "Bengaluru": ("Karnataka", ["Bangalore"], "bangalore"),
    "Mysuru": ("Karnataka", ["Mysore"], None),
    "Mangaluru": ("Karnataka", ["Mangalore"], "Mangalore"),
    "Hubballi": ("Karnataka", ["Hubli", "Dharwad"], None),
    "Chennai": ("Tamil Nadu", ["Madras"], "chennai"),
    "Coimbatore": ("Tamil Nadu", ["Kovai"], "Coimbatore"),
    "Madurai": ("Tamil Nadu", [], "Madurai"),
    "Tiruchi": ("Tamil Nadu", ["Tiruchirappalli", "Trichy"], "Tiruchirapalli"),
    "Salem": ("Tamil Nadu", [], None),
    "Hyderabad": ("Telangana", ["Secunderabad"], "Hyderabad"),
    "Visakhapatnam": ("Andhra Pradesh", ["Vizag"], "Visakhapatnam"),
    "Vijayawada": ("Andhra Pradesh", [], "Vijayawada"),
    "Kochi": ("Kerala", ["Cochin", "Ernakulam"], "Kochi"),
    "Thiruvananthapuram": ("Kerala", ["Trivandrum"], "Thiruvananthapuram"),
    "Kozhikode": ("Kerala", ["Calicut"], None),
    "Puducherry": ("Puducherry", ["Pondicherry"], "puducherry"),
    "Mumbai": ("Maharashtra", ["Bombay", "Thane", "Navi Mumbai"], "mumbai"),
    "Pune": ("Maharashtra", [], None),
    "Nagpur": ("Maharashtra", [], None),
    "New Delhi": ("Delhi", ["Delhi", "Noida", "Gurugram", "Gurgaon"], "Delhi"),
    "Kolkata": ("West Bengal", ["Calcutta", "Howrah"], "kolkata"),
    "Ahmedabad": ("Gujarat", ["Gandhinagar"], None),
    "Surat": ("Gujarat", [], None),
    "Jaipur": ("Rajasthan", [], None),
    "Lucknow": ("Uttar Pradesh", [], None),
    "Kanpur": ("Uttar Pradesh", [], None),
    "Varanasi": ("Uttar Pradesh", [], None),
    "Patna": ("Bihar", [], None),
    "Bhopal": ("Madhya Pradesh", [], None),
    "Indore": ("Madhya Pradesh", [], None),
    "Chandigarh": ("Chandigarh", ["Mohali"], None),
    "Bhubaneswar": ("Odisha", ["Cuttack"], None),
    "Guwahati": ("Assam", [], None),
    "Goa": ("Goa", ["Panaji"], None),
    "Dehradun": ("Uttarakhand", [], None),
    "Srinagar": ("Jammu and Kashmir", [], None),
    "Ranchi": ("Jharkhand", [], None),
    "Raipur": ("Chhattisgarh", [], None),
}

STATE_HINDU_SLUG = {
    "Karnataka": "karnataka", "Tamil Nadu": "tamil-nadu", "Kerala": "kerala",
    "Andhra Pradesh": "andhra-pradesh", "Telangana": "telangana",
}

COUNTRIES = ["United States", "US", "USA", "China", "Pakistan", "Bangladesh", "Sri Lanka", "Nepal", "Russia",
             "Ukraine", "Israel", "Iran", "Gaza", "UK", "Britain", "France", "Germany", "Japan", "Australia",
             "Canada", "Afghanistan", "Myanmar", "Maldives", "Saudi Arabia", "UAE", "Dubai", "Singapore"]

_place_terms: list[tuple[str, str, str]] = []  # (alias, canonical, kind)
for _c, (_s, _aliases, _) in CITIES.items():
    for _a in [_c, *_aliases]:
        _place_terms.append((_a, _c, "city"))
for _s in STATES:
    _place_terms.append((_s, _s, "state"))
for _k in COUNTRIES:
    _place_terms.append((_k, _k, "country"))
_place_terms.append(("India", "India", "country"))
_PLACE_RE = re.compile(
    r"(?<![\w-])(" + "|".join(re.escape(a) for a, _, _ in sorted(_place_terms, key=lambda x: -len(x[0]))) + r")(?![\w-])"
)
_PLACE_LOOKUP = {a.lower(): (c, k) for a, c, k in _place_terms}


def find_places(text: str) -> list[str]:
    out: list[str] = []
    for m in _PLACE_RE.finditer(text):
        canon, _ = _PLACE_LOOKUP[m.group(1).lower()]
        if canon not in out:
            out.append(canon)
    return out


def place_kind(name: str) -> str | None:
    hit = _PLACE_LOOKUP.get(name.lower())
    return hit[1] if hit else None


def story_tier(places: list[str], prefs: dict, source_tier: str | None) -> str:
    city, state = prefs.get("city"), prefs.get("state")
    city_aliases = {city} | set(CITIES.get(city, ("", [], None))[1]) if city else set()
    if any(p in city_aliases for p in places):
        return "city"
    for p in places:
        if p == state or (p in CITIES and CITIES[p][0] == state):
            return "state"
    if source_tier in ("city", "state") and not places:
        return source_tier
    foreign = [p for p in places if place_kind(p) == "country" and p != "India"]
    indian = [p for p in places if place_kind(p) in ("city", "state") or p == "India"]
    if foreign and not indian:
        return "world"
    if indian:
        return "india"
    return source_tier or "india"


# ---------------------------------------------------------------- text helpers
_STOP = set("""a an the and or but if of to in on at for with by from as is are was were be been being this that these those
it its into over after before under about than then so not no new says say said will would can could may might
amid against during while who what when where why how which top latest live updates news today watch video photos
report reports breaking exclusive here there his her their our your my we you he she they them us i
mr mrs ms dr sri shri smt govt government state city district police court
january february march april may june july august september october november december
monday tuesday wednesday thursday friday saturday sunday jan feb mar apr jun jul aug sep sept oct nov dec
also more most just like get gets got one two three amid via per year years day days week weeks month months
first last next people time times know check full list
four five six seven eight nine ten dozen hundreds thousands score scores live cricket match""".split())

_ENTITY_RE = re.compile(r"\b([A-Z][\w'’.-]+(?:\s+(?:of|de|the|and|&)?\s*[A-Z][\w'’.-]+)*)")


def entities(text: str) -> list[str]:
    """Rough named-entity pull: capitalised word runs, minus stopwords and sentence starts."""
    out: list[str] = []
    for sent in re.split(r"(?<=[.!?:|])\s+|\s[-–—]\s", text):
        for i, m in enumerate(_ENTITY_RE.finditer(sent)):
            phrase = m.group(1).strip(" .'’-")
            words = phrase.split()
            if len(words) == 1:
                w = words[0].lower()
                if w in _STOP or len(w) < 3:
                    continue
                if m.start() == 0 and w in _STOP:
                    continue
            phrase = re.sub(r"['’]s$", "", phrase)
            if phrase.lower() in _STOP:
                continue
            if phrase not in out:
                out.append(phrase)
    return out[:20]


def norm_title(title: str) -> str:
    t = unicodedata.normalize("NFKD", title).lower()
    t = re.sub(r"[^\w\s]", " ", t)
    return re.sub(r"\s+", " ", t).strip()


def detect_lang(text: str) -> str:
    if not text:
        return "en"
    deva = sum(1 for ch in text if "ऀ" <= ch <= "ॿ")
    tamil = sum(1 for ch in text if "஀" <= ch <= "௿")
    kan = sum(1 for ch in text if "ಀ" <= ch <= "೿")
    n = max(len(text), 1)
    if deva / n > 0.2:
        return "hi"
    if tamil / n > 0.2:
        return "ta"
    if kan / n > 0.2:
        return "kn"
    return "en"


_OPINION_RE = re.compile(r"/(opinion|opinions|editorial|editorials|columns?|blogs?|analysis|explained|explainer|lead)/", re.I)
_OPINION_TITLE = re.compile(r"^(opinion|editorial|explained|explainer|analysis|view|comment)\s*[:|]|\bexplained\b|what we know|\bwhy .* matters\b", re.I)
_LIVE_RE = re.compile(r"(/live[-/]|/liveblog|live[- ]updates|live blog|\blive:)", re.I)


def article_kind(url: str, title: str) -> str:
    if _LIVE_RE.search(url) or _LIVE_RE.search(title):
        return "live"
    if _OPINION_RE.search(url) or _OPINION_TITLE.search(title):
        return "opinion"
    if re.search(r"/videos?/", url, re.I):
        return "video"
    return "report"


def clean_html(s: str | None) -> str:
    if not s:
        return ""
    s = re.sub(r"<[^>]+>", " ", s)
    s = re.sub(r"&nbsp;|&#160;", " ", s)
    s = re.sub(r"&amp;", "&", s)
    s = re.sub(r"&quot;|&#34;", '"', s)
    s = re.sub(r"&#39;|&apos;|&rsquo;", "’", s)
    s = re.sub(r"&lt;", "<", s)
    s = re.sub(r"&gt;", ">", s)
    return re.sub(r"\s+", " ", s).strip()


def short_summary(text: str, max_words: int = 45) -> str:
    text = clean_html(text)
    words = text.split()
    if len(words) <= max_words:
        return text
    cut = " ".join(words[:max_words])
    # end at sentence boundary if one is reasonably close
    m = re.search(r"^(.{120,}?[.!?])\s", cut + " ")
    return m.group(1) if m else cut.rstrip(",;:") + "…"


def key_tokens(ents: list[str]) -> set[str]:
    """Word-level key names from entity phrases ("Diljit Dosanjh's Bengaluru" -> diljit, dosanjh, bengaluru)."""
    out = set()
    for e in ents:
        for w in re.split(r"[\s/-]+", e):
            w = re.sub(r"['’]s$", "", w).strip(".,:;'’\"()").lower()
            if len(w) >= 3 and w not in _STOP and not w.isdigit():
                out.add(w)
    return out


_WORD_RE = re.compile(r"[a-z][a-z'’-]{3,}|\d[\d,.]*")


def content_words(text: str) -> set[str]:
    return {w.strip("'’-") for w in _WORD_RE.findall(text.lower()) if w not in _STOP}


def user_tier(story: dict, prefs: dict) -> str:
    """Where a story sits for this user (city / state / india / world).

    Places the story mentions decide first; which city/state feed it arrived through is only a
    fallback for stories that name no Indian place (a state feed also carries national news).
    """
    city, state = prefs.get("city"), prefs.get("state")
    places = story.get("places") or []
    aliases = ({city} | set(CITIES.get(city, ("", [], None))[1])) if city else set()
    if any(p in aliases for p in places):
        return "city"
    if any(p == state or (p in CITIES and CITIES[p][0] == state) for p in places):
        return "state"
    indian = [p for p in places if place_kind(p) in ("city", "state")]
    foreign = [p for p in places if place_kind(p) == "country" and p != "India"]
    if not indian:
        if city in (story.get("src_cities") or []):
            return "city"
        if state in (story.get("src_states") or []):
            return "state"
    if foreign and not indian and "India" not in places:
        return "world"
    if indian or "India" in places:
        return "india"
    return story.get("tier_hint") or "india"
