"""
Download the calibration photo set: one Wikimedia Commons category per query.

The label is the category, not a search hit: a photo is on topic for a query because a human filed it
under that subject. That is the ground truth calibrate.py measures against - positives for a query
are the photos of its category, and every other category is a negative.

Deterministic: category members are sorted by title before the first N are taken, files are rejected
by an explicit rule (format, size, aspect, a few title words), and manifest.json records the sha256
and byte count of everything downloaded. Re-running without --refresh only fetches what is missing.

Commons throttles hard, so requests are paced and only pre-rendered thumbnails are requested -
originals sit behind a much stricter limit (Retry-After: 120).

Usage:
    python tools/search_calibration/fetch_set.py
    python tools/search_calibration/fetch_set.py --per-category 16 --refresh
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

HERE = os.path.dirname(os.path.abspath(__file__))
PHOTO_DIR = os.path.join(HERE, "photos")
MANIFEST = os.path.join(HERE, "manifest.json")
API = "https://commons.wikimedia.org/w/api.php"
USER_AGENT = "DeepixSearchCalibration/1.0 (local on-device model calibration)"

# (slug, Commons category, the query the calibrator asks, diagnostic only)
CATEGORIES = [
    ("dogs", "Category:Dogs", "dog", False),
    ("cats", "Category:Cats", "cat", False),
    ("red-cars", "Category:Red automobiles", "red car", False),
    ("blue-shirts", "Category:Blue shirts", "blue shirt", False),
    ("screenshots", "Category:Screenshots", "screenshot", False),
    ("beaches", "Category:Beaches", "beach", False),
    ("bicycles", "Category:Bicycles", "bicycle", False),
    ("flowers", "Category:Flowers", "flower", False),
    ("sunsets", "Category:Sunsets", "sunset", False),
    ("portraits", "Category:Portrait photographs", "person", False),
    ("meals", "Category:Food", "food", False),
    # Too few members to score as a category, but the exact query this calibration exists for.
    ("blue-shirt-people", "Category:People wearing blue shirts", "blue shirt person", True),
]

SKIP_TITLE_WORDS = (
    "map", "diagram", "logo", "icon", "flag", "chart", "graph", "coat of arms", "sign",
    "drawing", "painting", "engraving", "sculpture", "statue",
)
ALLOWED_MIME = ("image/jpeg", "image/png")

_LAST = [0.0]
_MIN_INTERVAL = 1.5


def _throttle() -> None:
    wait = _MIN_INTERVAL - (time.monotonic() - _LAST[0])
    if wait > 0:
        time.sleep(wait)
    _LAST[0] = time.monotonic()


def _get(url: str, timeout: int = 60) -> bytes:
    _throttle()
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.read()


def _get_with_retry(url: str, attempts: int = 3) -> bytes:
    for attempt in range(attempts):
        try:
            return _get(url)
        except urllib.error.HTTPError as error:
            if error.code != 429 or attempt == attempts - 1:
                raise
            retry_after = error.headers.get("Retry-After") if error.headers else None
            wait = min(120.0, float(retry_after) if retry_after and retry_after.isdigit() else 15.0)
            time.sleep(wait)
    raise RuntimeError("unreachable")


def category_members(category: str, limit: int) -> list[dict]:
    query = {
        "action": "query",
        "format": "json",
        "generator": "categorymembers",
        "gcmtitle": category,
        "gcmtype": "file",
        "gcmlimit": str(limit),
        "prop": "imageinfo",
        "iiprop": "url|size|mime",
        "iiurlwidth": "640",  # a standard bucket; arbitrary widths are what Commons throttles
    }
    payload = json.loads(_get_with_retry(API + "?" + urllib.parse.urlencode(query)))
    pages = payload.get("query", {}).get("pages", {})
    return sorted(pages.values(), key=lambda page: page.get("title", ""))


def usable(info: dict, title: str) -> bool:
    mime = info.get("mime", "")
    width, height = int(info.get("width") or 0), int(info.get("height") or 0)
    if mime not in ALLOWED_MIME or min(width, height) < 400:
        return False
    if max(width, height) / max(1, min(width, height)) > 2.5:
        return False
    # thumburl equals the original for files narrower than the bucket, and originals sit behind a
    # much stricter limit (429 with Retry-After: 120). Only the pre-rendered thumbnail host is safe.
    if not str(info.get("thumburl", "")).startswith("https://thumb.wikimedia.org/"):
        return False
    lowered = title.lower()
    return not any(word in lowered for word in SKIP_TITLE_WORDS)


def slugify(title: str, index: int) -> str:
    stem = title.split(":", 1)[-1].rsplit(".", 1)[0]
    ascii_stem = stem.encode("ascii", "ignore").decode("ascii")
    safe = "".join(ch if ch.isalnum() or ch in "-_" else "_" for ch in ascii_stem)[:60].strip("_")
    return f"{index:02d}_{safe or 'photo'}.jpg"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--per-category", type=int, default=20)
    parser.add_argument("--refresh", action="store_true")
    args = parser.parse_args()

    os.makedirs(PHOTO_DIR, exist_ok=True)
    entries: list[dict] = []
    skipped: list[dict] = []

    for slug, category, query, diagnostic in CATEGORIES:
        try:
            pages = category_members(category, limit=max(60, args.per_category * 3))
        except Exception as error:
            skipped.append({"slug": slug, "reason": f"query failed: {error}"})
            print(f"[skip] {slug}: {error}", flush=True)
            continue

        folder = os.path.join(PHOTO_DIR, slug)
        os.makedirs(folder, exist_ok=True)
        taken = 0
        for page in pages:
            if taken >= args.per_category:
                break
            info = (page.get("imageinfo") or [{}])[0]
            title = page.get("title", "")
            if not usable(info, title):
                continue
            name = slugify(title, taken)
            path = os.path.join(folder, name)
            if not os.path.exists(path) or args.refresh:
                try:
                    data = _get_with_retry(info["thumburl"])
                except Exception as error:
                    print(f"[skip] {slug}/{name}: {error}", flush=True)
                    continue
                with open(path, "wb") as handle:
                    handle.write(data)
            with open(path, "rb") as handle:
                data = handle.read()
            entries.append(
                {
                    "slug": slug,
                    "query": query,
                    "category": category,
                    "diagnostic": diagnostic,
                    "title": title,
                    "path": os.path.relpath(path, HERE).replace("\\", "/"),
                    "source": info.get("descriptionurl"),
                    "width": info.get("width"),
                    "height": info.get("height"),
                    "bytes": len(data),
                    "sha256": hashlib.sha256(data).hexdigest(),
                }
            )
            taken += 1
        print(f"[ok]   {slug}: {taken} photos", flush=True)
        if taken < 8:
            skipped.append({"slug": slug, "reason": f"only {taken} usable members"})

    manifest = {
        "source": "Wikimedia Commons category members; 640px thumbnail bucket",
        "per_category": args.per_category,
        "photos": entries,
        "skipped": skipped,
    }
    with open(MANIFEST, "w", encoding="utf-8") as handle:
        json.dump(manifest, handle, indent=2, sort_keys=True)
    by_slug: dict[str, int] = {}
    for entry in entries:
        by_slug[entry["slug"]] = by_slug.get(entry["slug"], 0) + 1
    print(json.dumps({"photos": len(entries), "by_slug": by_slug, "skipped": skipped}, indent=2))


if __name__ == "__main__":
    main()
