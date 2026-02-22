# Aniyomi Fork — Ultimate Manga Reader App

## Project Goal
Build the ultimate manga reader Android app based on Aniyomi, with comix.to as a built-in source that auto-updates with new manga/chapters.

## Owner
GitHub: **Mythical365**
Fork URL: https://github.com/Mythical365/aniyomi
Local path: `/Users/mody/Documents/aniyomi`
Chrome extension: `/Users/mody/Documents/comixto-inspector`

---

## Key Architecture

### Source System
Sources extend either `HttpSource` or `ParsedHttpSource` from:
`source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/online/`

Built-in sources are registered in:
`app/src/main/java/eu/kanade/tachiyomi/source/manga/AndroidMangaSourceManager.kt`

Add new built-in sources the same way `LocalMangaSource` is added — in the `mutableMap` inside `init {}`.

### Domain Models
| Model | File |
|-------|------|
| SManga | `source-api/.../source/model/SManga.kt` |
| SChapter | `source-api/.../source/model/SChapter.kt` |
| Page | `source-api/.../source/model/Page.kt` |
| MangasPage | `source-api/.../source/model/MangasPage.kt` |

### Data Layer
- Paging: `data/src/main/java/tachiyomi/data/source/manga/MangaSourcePagingSource.kt`
- Page loading: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/HttpPageLoader.kt`

---

## comix.to Source (IN PROGRESS)

### Site Info
- URL: https://comix.to
- Framework: Next.js 13+ App Router (React Server Components)
- Stats: 662 manga, 75k+ chapters
- CDN for images: `https://static.comix.to/`

### Known URL Patterns
```
Browse:   /browser?types=manhwa,manhua&sort=updated_at
Manga:    /title/{hash_id}-{slug}
Chapter:  /title/{hash_id}-{slug}/{chapter_id}-{chapter_name}
```

### Data Structure (from embedded RSC JSON)
```json
{
  "manga_id": 1429,
  "hash_id": "n8we",
  "title": "...",
  "slug": "...",
  "type": "manhwa",
  "status": "releasing",
  "synopsis": "...",
  "poster": {
    "small": "https://static.comix.to/3898/i/e/1f/68e0868f97c82@100.jpg",
    "medium": "https://static.comix.to/3898/i/e/1f/68e0868f97c82@280.jpg",
    "large": "https://static.comix.to/3898/i/e/1f/68e0868f97c82.jpg"
  },
  "creators": { "authors": [...], "artists": [...] },
  "genres": ["Comedy", "Drama", "Fantasy"],
  "latest_chapter": 58,
  "chapter_updated_at": 1771719061,
  "rated_avg": 9.5,
  "follows_total": 5708
}
```

### API Endpoints (DISCOVERED)
- **Chapter list**: `GET /api/v2/manga/{hash_id}/chapters?limit=500&page=1&order[number]=desc`
- **Page images**: Embedded in chapter page RSC response — hosted at external CDN `*.store/ii/{token}/{num}.webp`
- **Browse/search**: Parse RSC data from `/browser?sort=updated_at&page=N`

### Source File (to create)
Path: `app/src/main/java/eu/kanade/tachiyomi/source/manga/comixto/ComixtoSource.kt`

Base class to use: `HttpSource` (not `ParsedHttpSource` since site uses RSC/JSON not classic HTML)

Register it in `AndroidMangaSourceManager.kt` in the `mutableMap` alongside `LocalMangaSource`.

---

## Feature Roadmap

### Phase 1 — comix.to Source
- [ ] Manga listing (browse/popular/latest)
- [ ] Search
- [ ] Manga details
- [ ] Chapter list (needs API discovery)
- [ ] Page images / reading (needs API discovery)
- [ ] Auto-update: poll `/browser?sort=updated_at` periodically

### Phase 2 — Reader UI
- [ ] Smooth webtoon scroll mode
- [ ] Paged mode with animations
- [ ] Better chapter controls (prev/next, page indicator)

### Phase 3 — Library & Downloads
- [ ] Auto-download new chapters
- [ ] Offline reading
- [ ] Library sync

---

## Build & Dev

```bash
# Build the app
./gradlew assembleDebug

# Run tests
./gradlew test

# Install on connected device
./gradlew installDebug
```

Requires Android SDK. Main app module: `app/`
