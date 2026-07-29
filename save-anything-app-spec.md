# Weavr — Full Product & Technical Specification
### AI-powered "save anything" execution engine — RevenueCat Shipaton 2026 submission

> **This document is partly superseded.** It is kept as the original product
> spec; the stack decisions in [CLAUDE.md](CLAUDE.md) override it wherever they
> disagree — see *Where the spec is stale* there. Sections 2, 3, 9–13 are still
> current.

**Team:** Arth + friend, two-person vibe-coded build
**Window:** Aug 1 – Sep 30, 2026
**Name:** locked as **Weavr** (Jul 2026). Bundle/package id `com.weavr.app`, Java package `com.weavr.api`, Expo slug `weavr`.

---

## 0. One-line pitch

> Share anything. AI understands it, organizes it, and turns it into something you actually do.

Not a notes app, not a bookmark app, not a screenshot folder. A universal capture layer with an AI pipeline that converts saved content into structured, actionable, shareable knowledge — solo or with friends.

---

## 1. Repo analysis — what to build on, what to study, what to avoid

You asked which existing repo is the right fit. Short answer: **none of them should be forked as your app**, but three are worth mining hard for architecture.

| Repo | What it is | Verdict |
|---|---|---|
| **[karakeep-app/karakeep](https://github.com/karakeep-app/karakeep)** | Self-hosted bookmark-everything app, AI auto-tagging, full-text + semantic search, mobile apps, browser extensions. Next.js, Drizzle, SQLite, Puppeteer, BullMQ, Meilisearch. AGPL-3.0. | **Study, don't fork.** Closest existing match to your Capture+Understand+Organize pillars. Copy the *patterns* — job-queue-based background AI tagging, the bookmark/tag/AI-metadata schema shape — not the code. It's a Next.js web app, not mobile, and AGPL is incompatible with a closed-source paid app if you modify and redeploy it. |
| **[linkwarden/linkwarden](https://github.com/linkwarden/linkwarden)** | Self-hosted collaborative bookmark manager. AI tagging, collections, multi-user roles, full-page archival. AGPL-3.0. | **Study for the Spaces feature.** Its owner/editor/viewer permission model on collections is the best existing reference for your collaborative Spaces. Same license caveat as above. |
| **[pickeld/social_recipes](https://github.com/pickeld/social_recipes)**, **[Sous Clip](https://sous-clip-web.pages.dev/)**, **[recipe-extractor](https://github.com/sleeper/recipe-extractor)** | Small, single-purpose pipelines: yt-dlp downloads a TikTok/IG/YouTube video → Whisper transcribes audio → LLM turns it into structured JSON → pushed to Mealie/Tandoor. | **This is your actual blueprint.** It's the exact "reel → structured object" pipeline your idea needs, just narrowed to recipes. Read `social_recipes` end to end (it's a small Flask app, ~30 min read) — it solves your hardest technical unknown: reliably pulling video + captions off Instagram/TikTok. Reimplement generalized across all your knowledge types rather than importing the code wholesale (license clarity + you need it type-agnostic anyway). |
| Wallabag, Shiori, Omnivore, Linkding | Older/simpler read-it-later and bookmark tools. | Skim only if you want more schema ideas. Omnivore is a useful cautionary tale — it died partly from over-coupling to Google Cloud infra, don't repeat that. |
| **vojtaholik/mymind** (CLI) | Toy project where you "save" and "find" things by talking to Claude in natural language. | Not infrastructure, but a nice proof-of-concept for your "just say save, AI figures out the rest" UX philosophy. |

**Directly reusable tools (not forked apps — actual dependencies you'll install):**
- **yt-dlp** — the real workhorse for pulling video/audio/captions off Instagram, TikTok, YouTube. Permissively licensed, actively maintained.
- **ffmpeg** — audio extraction from downloaded video. Shell out to it as an external binary; this doesn't pull its license into your own code.
- **Whisper** — OpenAI's speech-to-text model is MIT-licensed, but for a two-person hackathon team, **don't self-host it.** Use a hosted API (Groq's `whisper-large-v3-turbo` is extremely fast and cheap) so you're not managing GPU infra.

---

## 2. Feature specification (full scope — nothing pre-cut)

Organized by the five pillars from your original doc. Every feature below is in scope; Section 11 gives a suggested *build order*, not a cut list.

### 2.1 Capture
- OS-level share sheet integration on iOS and Android (the single most important piece of plumbing — see §5)
- Accepts: Instagram Reels/posts, TikToks, YouTube/Shorts, Reddit posts, X/Twitter posts, Pinterest pins, articles/links, screenshots, camera photos, PDFs, voice memos, plain text, forwarded WhatsApp/Telegram messages (via native share sheet, no special API needed)
- In-app quick-add: paste a link, dictate a note, snap a photo
- Personal forwarding email address per user (forward a newsletter or email → auto-saved)
- Batch capture: share multiple items at once from a photo library

### 2.2 Understand (AI pipeline)
- Automatic classification into a Knowledge Type (see taxonomy below)
- Structured field extraction per type (ingredients+steps for a recipe, muscle groups+equipment for a workout, etc.)
- OCR/vision understanding for screenshots and photos (via vision-capable LLM call, no separate OCR step needed)
- Speech-to-text for video/voice content
- Metadata enrichment from external APIs (posters, ratings, streaming availability, addresses, hours)
- "Why did I save this?" — one-line reasoning of what prompted the save, extracted from the source caption/context
- Related-item suggestions once enough saves exist in a type

### 2.3 Organize
- Auto-tagging (AI) + manual tags
- Knowledge Type taxonomy: Entertainment (movies, TV, anime, books, games, podcasts, music) · Food (recipes, restaurants, cafés, meal plans, groceries) · Fitness (workouts, exercises, running, yoga, meditation) · Learning (courses, tutorials, coding, languages, career) · Shopping (products, wishlist, fashion, tech, gifts) · Travel (places, hotels, itineraries, packing) · Lifestyle (quotes, ideas, habits, journal prompts) · Finance (investment ideas, budgeting, side hustles) · Home (DIY, decor, plants, organization) · Creative (editing tips, photography, design inspiration) · Personal (people, events, gift ideas)
- Manual Collections (user-defined folders that cut across types)
- Hybrid search: keyword (Postgres full-text) + semantic/"vibe" search (pgvector embeddings) in one query
- Lifecycle status per item: Saved → Planned → Started → Completed → Reviewed → Archived

### 2.4 Act (your core differentiator — build this well)
Every saved object gets one primary call-to-action, not just a "view" button:

| Type | Primary action(s) |
|---|---|
| Recipe | Generate shopping list, scale servings, step-by-step cook mode with timers |
| Workout | Add to weekly routine, log sets/reps, calendar scheduling |
| Movie/TV | Add to watchlist, check streaming availability, "who's free tonight" friend ping |
| Restaurant/Café | Navigate (maps), reserve, visit checklist, vote with friends |
| Book | Start reading, add to reading challenge, notes |
| Travel/Place | Add to itinerary, budget estimate, nearby-saved-places on a map |
| Product | Price watch, wishlist, gift-tagging |
| Quote/Idea | Reflect/journal prompt, resurface later |

- **AI Weekly Digest** — push notification: "12 new saves, 3 completed, 4 recipes still waiting. Cook one this weekend?"
- **AI Project Builder** — detects clusters of related saves (e.g. 15 Japan-related items) and offers to bundle them into a structured Project (Places / Hotels / Food / Budget / Itinerary) automatically
- **AI Challenges** — "You've saved 40 workouts, completed 6 — start a 21-day plan?"

### 2.5 Collaborate
- Shared Spaces (multi-user collections) with real-time sync
- Roles: owner / editor / viewer
- Duplicate detection + merge when two people save the same restaurant/place (fuzzy match + embedding similarity)
- Lightweight comments and voting/polls within a Space ("which restaurant for movie night?")
- Invite via link or QR code
- Friend activity feed — meaningful updates only (completed, rated, visited), not a noisy social feed

---

## 3. What people actually pay for (monetization design)

This is the section that matters most for the hackathon. RevenueCat's own 2026 benchmark data (115k+ apps, $16B+ tracked revenue) found **hard paywalls convert roughly 5x better than freemium (10.7% vs 2.1%)**, with retention ending up about the same after a year. Design around that: let the AI "wow" moment happen fast and free, then gate volume and the highest-leverage features hard.

### Free tier (the hook, not a full product)
- 20 AI-processed saves per month (the cap is on *volume*, not quality — the first save must be full-fidelity magic or nobody converts)
- 1 personal Space only
- Manual keyword search (no semantic search)
- Can be *invited into* a friend's shared Space and use it fully — but can't create their own Space
- 1 "Act" conversion per week (1 shopping list, 1 workout plan, etc.)

### Pro — $6.99/mo or $39.99/yr (primary revenue line)
- Unlimited saves and AI enrichment
- Semantic ("vibe") search
- Unlimited Act conversions
- AI Weekly Digest + nudges
- AI Project Builder
- Data export (CSV/Notion/PDF)
- Can create 1 shared Space (up to 6 members)

### Crew — $11.99/mo or $69.99/yr
- Everything in Pro
- Unlimited Spaces, up to 15 members each
- Group voting/polls, duplicate-merge, group activity feed
- Priority AI processing (faster turnaround)

**Why the invite-to-collaborate loop matters commercially:** a free user who gets pulled into a Pro friend's Space experiences the paid collaborative feature firsthand, for free, as a guest — a classic Dropbox-style expansion loop. That's your cheapest acquisition channel and it's built into the product, not bolted on as a referral program.

**Onboarding sequencing:** get the user to their first completed save (raw Reel → structured card) in under 30 seconds, ideally before requiring a hard signup. Show the paywall immediately after that first "aha," not before — this matches the day-zero cancellation data (roughly 55% of trial cancellations happen on day zero when paywalls show too early without earned trust).

---

## 4. System architecture

```
┌─────────────────────────┐
│   Expo (RN + TS) App    │  iOS / Android
│  - Share Extension in   │
│  - Feed / Search / Act  │
│  - RevenueCat Paywall   │
└───────────┬──────────────┘
            │ Supabase JS client (auth, DB, storage, realtime)
            ▼
┌─────────────────────────┐        ┌──────────────────────────┐
│        Supabase          │◄──────►│  Ingestion Worker Service │
│ - Postgres + pgvector    │  HTTP  │  (Node or Python, Docker) │
│ - Auth (RLS per user)    │        │  on Railway / Fly.io      │
│ - Storage (media)        │        │  - yt-dlp (video pull)    │
│ - Realtime (Spaces sync) │        │  - ffmpeg (audio extract) │
│ - Edge Functions (glue)  │        │  - Groq Whisper (ASR)     │
└───────────┬──────────────┘        │  - Claude (classify+extract)│
            │                       │  - Enrichment API calls   │
            │                       └──────────────────────────┘
            ▼
┌─────────────────────────┐
│      RevenueCat          │  Entitlements: pro, crew
│  Webhook → Edge Function │  Syncs subscription state to
│  → subscriptions table   │  Supabase for server-side gating
└─────────────────────────┘
```

**Why a separate worker service, not just Supabase Edge Functions:** video download + ffmpeg processing needs real compute and binary dependencies that don't fit serverless execution limits. Keep Edge Functions for lightweight glue (webhooks, triggering the worker, simple CRUD) and put the heavy AI/media pipeline in a small always-on container. Railway or Fly.io are the fastest to stand up for a hackathon timeline.

---

## 5. Capture layer: the share-sheet integration

This is the single highest-leverage piece of engineering — if this friction point isn't near-instant, nothing else matters.

- **Library:** [`expo-share-intent`](https://www.npmjs.com/package/expo-share-intent) (MIT licensed, actively maintained, purpose-built for exactly this). Handles both iOS Share Extension and Android Share Intent with one shared React Native codebase.
- Requires a custom EAS dev client (not Expo Go) since it's a native module — plan for this from week 1, not as an afterthought.
- Flow: user taps Share on a Reel → picks your app → app opens directly to a lightweight "Saving..." confirmation screen → upload happens in the background → push notification when the structured card is ready.
- Alternative/supplement: [`expo-share-extension`](https://github.com/MaxAst/expo-share-extension) if you want a custom native-view mini-UI (like Pinterest's save sheet) instead of jumping into the full app — nicer UX, more setup work; treat as a stretch goal once the base flow works.

---

## 6. Data model (Supabase Postgres)

```sql
users (id, email, display_name, avatar_url, revenuecat_customer_id, created_at)

spaces (id, name, type[personal|shared], owner_id, created_at)
space_members (space_id, user_id, role[owner|editor|viewer], joined_at)

saves (
  id, user_id, space_id NULL,
  source_type[instagram|tiktok|youtube|reddit|x|pinterest|link|screenshot|photo|pdf|voice|text|email],
  source_url, raw_caption, media_storage_path,
  status[processing|ready|failed],
  knowledge_type[movie|tv|book|game|podcast|music|recipe|restaurant|cafe|
                 workout|course|product|place|hotel|quote|idea|habit|
                 investment|diy|editing_tip|person|event|other],
  structured_data JSONB,      -- type-specific extracted fields
  embedding VECTOR(1536),     -- for semantic search
  lifecycle_status[saved|planned|started|completed|reviewed|archived],
  created_at, updated_at
)

tags (id, name)
save_tags (save_id, tag_id)

collections (id, space_id, name, is_ai_generated bool)
collection_items (collection_id, save_id)

projects (id, space_id, name, ai_generated bool, structure JSONB)  -- AI Project Builder output

comments (id, save_id NULL, space_id NULL, user_id, body, created_at)
votes (id, save_id, user_id, value)

subscriptions (user_id, revenuecat_customer_id, entitlement[pro|crew], status, renews_at)
activity_feed (id, space_id, user_id, save_id, action[saved|completed|rated|joined], created_at)
```

`structured_data` stays JSONB rather than rigid columns per type — you have ~20 knowledge types with different schemas, and JSONB lets you add new types without migrations mid-hackathon.

---

## 7. AI pipeline — step by step

1. Client uploads raw payload to Supabase Storage, inserts a `saves` row with `status = processing`.
2. Edge Function triggers the worker service with the save ID.
3. **If social video URL:** yt-dlp downloads the video + pulls caption/metadata → ffmpeg extracts audio → Groq Whisper transcribes it.
   **If screenshot/photo:** send directly to a vision-capable Claude call (skip separate OCR).
   **If link/article:** fetch + extract readable text.
4. **Classification call** (small, fast): forced-JSON prompt → `{ knowledge_type, confidence }`.
5. **Extraction call:** forced-JSON prompt using the schema template for that specific `knowledge_type` (this mirrors the same "forced JSON output" pattern you already planned for Let Him Cook's verdict engine).
6. **Enrichment:** call the relevant external API based on type —
   - Movies/TV → TMDB
   - Books → Google Books API
   - Music/podcasts → Spotify Web API
   - Restaurants/cafés/places → Google Places API
   Merge results into `structured_data`.
7. **Embedding:** summarize `structured_data` to a short text blob, embed with `text-embedding-3-small` (or Voyage AI), store in `saves.embedding`.
8. Update the row to `status = ready`, push a notification, and — if this save landed in a shared Space — run duplicate-detection against existing saves in that Space and write to `activity_feed`.

---

## 8. Tech stack summary

| Layer | Choice | Notes |
|---|---|---|
| Mobile app | Expo (React Native + TypeScript), EAS Build | Keeps continuity with your existing stack knowledge |
| Navigation/state | Expo Router, React Query, Zustand | |
| Share capture | `expo-share-intent` | MIT licensed, requires EAS dev client |
| Backend | Supabase (Postgres, Auth, Storage, Realtime, Edge Functions) | RLS for per-user/per-Space data isolation |
| Search | Postgres full-text + `pgvector` | Avoids standing up a separate Meilisearch service |
| Ingestion worker | Node or Python container on Railway/Fly.io | Runs yt-dlp, ffmpeg, orchestrates AI calls |
| Video download | yt-dlp | |
| Speech-to-text | Groq `whisper-large-v3-turbo` API | Hosted, fast, cheap — don't self-host Whisper |
| Classification/extraction | Claude (Sonnet), forced JSON schema per type | Same pattern as your existing prompt template approach |
| Vision/OCR | Claude vision, single call | Skips a separate OCR step |
| Embeddings | OpenAI `text-embedding-3-small` or Voyage AI | Stored in pgvector |
| Enrichment APIs | TMDB, Google Places, Google Books, Spotify Web API | Free/cheap tiers available |
| Monetization | `react-native-purchases` + `react-native-purchases-ui` (RevenueCat) | Native module — needs EAS build, not Expo Go |
| Push notifications | Expo Push | Weekly digest, ready-to-view, nudges |
| Realtime collab | Supabase Realtime | Shared Space sync |

---

## 9. RevenueCat + Shipaton integration (step by step)

**Hard hackathon requirement, confirmed from the official rules:** entrants must build a working app that uses the RevenueCat SDK to power at least one in-app or web purchase, built for iOS/iPadOS/macOS or Android, with the first public version released between August 1 and September 30, 2026.

1. Create a RevenueCat project; connect your App Store Connect and Google Play Console apps.
2. Define **entitlements**: `pro`, `crew`.
3. Create the actual subscription products in App Store Connect and Play Console first (`pro_monthly`, `pro_annual`, `crew_monthly`, `crew_annual`), then import them into RevenueCat and attach to the matching entitlement.
4. Build your **Offering** in the RevenueCat dashboard, and use `react-native-purchases-ui`'s prebuilt paywall templates for speed rather than hand-rolling paywall UI — this alone saves days.
5. `npm install react-native-purchases react-native-purchases-ui`, call `Purchases.configure({ apiKey })` on launch, then `Purchases.logIn(supabaseUserId)` to link RevenueCat's identity to your own user ID.
6. Gate client-side UI using `Purchases.getCustomerInfo()` entitlement checks — **and** mirror the same check server-side: set up a RevenueCat webhook → Supabase Edge Function → write to the `subscriptions` table, so the ingestion worker can enforce AI-call limits server-side too (don't let a client-only check gate your most expensive resource — that's a straightforward workaround for anyone who inspects network traffic).
7. Use RevenueCat's paywall placement tools to test showing the paywall right after the first completed save, per the conversion data in §3.
8. Test purchases with iOS Sandbox testers and Android License testers before submission.
9. This satisfies the core Shipaton technical requirement. Separately, the **Grand Prize** is judged on user traction and growth *during* the event — so shipping early in the window and actively driving installs matters as much as the build itself.

---

## 10. Suggested build order (8 weeks, Aug 1 – Sep 30)

Everything in Section 2 is in scope — this is a sequencing plan so you always have something demoable, not a cut list.

- **Week 1 — Foundation:** Expo skeleton, Supabase schema + auth, RevenueCat project + entitlements set up, EAS dev client working, `expo-share-intent` wired end-to-end (even with AI stubbed out).
- **Week 2 — AI pipeline v1:** worker service live, yt-dlp + Whisper + Claude working end-to-end for 3 knowledge types (movie, recipe, place), structured cards rendering in the feed.
- **Week 3 — Expand types + enrichment:** remaining knowledge types, TMDB/Places/Books/Spotify enrichment, embeddings + semantic search.
- **Week 4 — Organize layer:** collections, tags, lifecycle status, hybrid search UI.
- **Week 5 — Act layer:** per-type CTAs, recipe shopping list + cook mode, workout tracker, AI Weekly Digest, AI Project Builder v1.
- **Week 6 — Collaborate layer:** Spaces, invites, roles, Realtime sync, duplicate-merge, voting, activity feed.
- **Week 7 — Monetization polish:** paywall placement/copy, client+server entitlement gating, pricing finalized, onboarding tuned for fast-aha-then-paywall.
- **Week 8 — Growth & submission:** store listings, demo video (3 minutes, hook fast — judges watch dozens of these), #BuildInPublic posts, bug bash, Devpost submission, and keep shipping/promoting afterward since the Grand Prize is scored on post-release growth.

---

## 11. Growth & virality mechanics

- **Invite-to-collaborate loop:** the free-tier "join a friend's Space for free" mechanic (§3) is your main organic growth channel — build it early, not as a v2 add-on.
- **Shareable save cards:** every structured save can render as a clean, screenshot-ready visual card, shareable outside the app to Instagram Stories or WhatsApp — cheap, constant top-of-funnel exposure, same principle as the verdict-card loop you designed for Let Him Cook.
- **#BuildInPublic:** Shipaton has a separate prize track judged on how visibly and creatively you share the build process — post progress regularly, tag appropriately, and treat public feedback as a real input to the roadmap, since judges score whether you actually acted on it.

---

## 12. Legal, privacy, and cost risk notes

- **Scraping social platforms:** pulling video via yt-dlp from Instagram/TikTok sits in a legal gray area relative to those platforms' Terms of Service, even though it's extremely widely used. Mitigate by only ever pulling content the *user themselves* shares (never bulk-scraping public content), not publicly rehosting downloaded media, and being ready to honor takedown requests.
- **Storage costs:** delete raw downloaded video/audio once a transcript and thumbnail are extracted — don't keep full video copies indefinitely, both for cost and legal exposure.
- **AGPL repos:** if you reference Karakeep or Linkwarden source directly, don't copy/modify their code into your closed-source app — study the architecture, write your own implementation.
- **Privacy policy:** required for App Store/Play Store submission regardless of hackathon status — you're ingesting personal photos, screenshots, and possibly PDFs/emails, so this isn't optional boilerplate.
- **LLM/API cost control:** every save costs real money (Claude call × 2, Whisper transcription, embeddings, enrichment APIs). Instrument free-tier caps at the server/worker level from week 1, not as an afterthought — a viral spike on an uncapped free tier can burn your API budget fast.

---

## 13. Shipaton submission checklist

- [ ] App built for iOS/iPadOS/macOS or Android
- [ ] RevenueCat SDK powering at least one real in-app purchase
- [ ] First public version released between Aug 1 – Sep 30, 2026
- [ ] Privacy policy published
- [ ] Devpost submission: app description, demo video (≤3 min, no unlicensed music/trademarks), links to store listing or TestFlight/APK
- [ ] #BuildInPublic posts tagged appropriately, linked in submission, if entering that track
- [ ] Post-submission: keep shipping and promoting through Sep 30 — Grand Prize is scored on traction *during* the event, not just the final build
