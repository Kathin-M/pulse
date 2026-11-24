"""Default feed list and helpers for generated (city / interest) feeds."""
from __future__ import annotations

from urllib.parse import quote_plus

from .taxonomy import CITIES, SECTION_TOPICS, STATE_HINDU_SLUG

GN_PARAMS = "hl=en-IN&gl=IN&ceid=IN:en"


def google_search_url(query: str, when: str = "1d") -> str:
    return f"https://news.google.com/rss/search?q={quote_plus(query + ' when:' + when)}&{GN_PARAMS}"


def default_sources() -> list[dict]:
    out = [
        {"url": f"https://news.google.com/rss?{GN_PARAMS}", "name": "Google News · India", "kind": "google", "tier": "india", "trust": 0.6},
    ]
    for section, topic in SECTION_TOPICS.items():
        out.append({
            "url": f"https://news.google.com/rss/headlines/section/topic/{section}?{GN_PARAMS}",
            "name": f"Google News · {section.title()}", "kind": "google",
            "tier": "world" if section == "WORLD" else "india", "topic_hint": topic, "trust": 0.6,
        })
    outlets = [
        ("https://www.thehindu.com/news/national/feeder/default.rss", "The Hindu · National", "india", None, 0.8),
        ("https://www.thehindu.com/news/international/feeder/default.rss", "The Hindu · World", "world", "World", 0.8),
        ("https://www.thehindu.com/business/feeder/default.rss", "The Hindu · Business", "india", "Business", 0.8),
        ("https://www.thehindu.com/sci-tech/feeder/default.rss", "The Hindu · Sci-Tech", "india", "Science", 0.8),
        ("https://www.thehindu.com/sport/feeder/default.rss", "The Hindu · Sport", "india", "Sports", 0.8),
        ("https://indianexpress.com/section/india/feed/", "Indian Express · India", "india", None, 0.75),
        ("https://feeds.feedburner.com/ndtvnews-top-stories", "NDTV · Top stories", "india", None, 0.65),
        ("https://www.hindustantimes.com/feeds/rss/india-news/rssfeed.xml", "Hindustan Times · India", "india", None, 0.65),
        ("https://www.livemint.com/rss/news", "Mint · News", "india", "Business", 0.7),
        ("https://www.livemint.com/rss/companies", "Mint · Companies", "india", "Business", 0.7),
        ("https://timesofindia.indiatimes.com/rssfeedstopstories.cms", "Times of India · Top", "india", None, 0.55),
        ("https://feeds.bbci.co.uk/news/world/rss.xml", "BBC · World", "world", "World", 0.8),
        ("https://hnrss.org/frontpage?points=200", "Hacker News · Front page", "world", "Technology", 0.6),
    ]
    for url, name, tier, topic, trust in outlets:
        out.append({"url": url, "name": name, "kind": "outlet", "tier": tier, "topic_hint": topic, "trust": trust})
    return out


def city_sources(city: str, state: str) -> list[dict]:
    """Feeds for one city and its state (tagged so each user only gets their own)."""
    out = _city_sources(city, state)
    for s in out:
        s["city"] = city if s.get("tier") == "city" else None
        s["state"] = state
    return out


def _city_sources(city: str, state: str) -> list[dict]:
    out = [{
        "url": google_search_url(f'"{city}"'), "name": f"Google News · {city}", "kind": "city",
        "tier": "city", "query": f'"{city}"', "trust": 0.6,
    }]
    slug = CITIES.get(city, (None, [], None))[2]
    if slug:
        out.append({
            "url": f"https://www.thehindu.com/news/cities/{slug}/feeder/default.rss",
            "name": f"The Hindu · {city}", "kind": "city", "tier": "city", "trust": 0.8,
        })
    if city == "Bengaluru":
        out.append({"url": "https://indianexpress.com/section/cities/bangalore/feed/",
                    "name": "Indian Express · Bengaluru", "kind": "city", "tier": "city", "trust": 0.75})
        out.append({"url": "https://timesofindia.indiatimes.com/rssfeeds/-2128833038.cms",
                    "name": "Times of India · Bengaluru", "kind": "city", "tier": "city", "trust": 0.55})
    if city == "Chennai":
        out.append({"url": "https://indianexpress.com/section/cities/chennai/feed/",
                    "name": "Indian Express · Chennai", "kind": "city", "tier": "city", "trust": 0.75})
    st_slug = STATE_HINDU_SLUG.get(state)
    if st_slug:
        out.append({
            "url": f"https://www.thehindu.com/news/national/{st_slug}/feeder/default.rss",
            "name": f"The Hindu · {state}", "kind": "city", "tier": "state", "trust": 0.8,
        })
    else:
        out.append({"url": google_search_url(f'"{state}"'), "name": f"Google News · {state}", "kind": "city",
                    "tier": "state", "query": f'"{state}"', "trust": 0.6})
    return out
