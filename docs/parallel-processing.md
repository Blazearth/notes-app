# Parallel processing and free-tier capacity

An analysis of why Weavr degrades under concurrent saves, what the actual
binding constraints are, and the cheapest architecture that removes them.

**Written 2026-08-15** against the code as it stands at commit `75b7caf`.
Every claim about the codebase below was read out of the source, not recalled.
Every claim about a hosting provider was checked against current documentation
and is dated. Durations marked *(est.)* are derived from configured timeouts and
known behaviour — **they have not been measured in production**, and
[§8](#8-capacity-estimate) says exactly which measurement would replace them.

---

## 0. The premise needs correcting first

The brief describes a synchronous server that holds an HTTP request open for the
duration of extraction. **That is not this system.** It was already built the way
the brief proposes:

| Brief asks for | Status in `main` | Where |
|---|---|---|
| `POST /save` returns immediately | **Already true** — returns `202` with a `Location` header | `SaveService.create` |
| Durable job queue | **Already true** — Postgres, `FOR UPDATE SKIP LOCKED` | `JobStore.claim` |
| Atomic claim, no double-processing | **Already true** | `JobStore.claim:61-94` |
| Retries, exponential backoff, max attempts | **Already true** — 30s → 2m → 8m → 32m, capped 1h | `JobStore.backoffFor` |
| Stale-job recovery | **Already true** — 15-minute reaper | `JobStore.requeueStale` |
| Idempotent jobs | **Already true** — `jobs.idempotency_key` unique, `on conflict do nothing` | `JobQueue.enqueue` |
| Extraction separated from enrichment | **Already true** — 4 distinct job types | `JobType` |
| AI budget tracked durably | **Already true** — `ai_budget_days`, US/Pacific reset | `GeminiBudgetService` |
| Rate-limit exhaustion doesn't burn retries | **Already true** — `RetryAfterException` costs no attempt | `JobRunner.run:159` |
| Client never blocks on a long request | **Already true** — local-first store + 2s poll | `SavesProvider`, `docs/local-first.md` |

Rebuilding any of that would be re-solving solved problems. **The failure under
concurrent load has a different cause, and four of the six real constraints are
not in the queue at all.**

---

## 1. Current bottleneck — why parallel saves actually degrade

There is one dominant cause and it is a single line of configuration:

```yaml
# render.yaml
- key: WEAVR_JOBS_CONCURRENCY
  value: "1"              # free tier: 1 parallel job to avoid OOM
```

**Every job type shares one worker slot.** `JobRunner` holds a single
`Semaphore(concurrency)` and one fixed thread pool; a `classify_save` (one HTTPS
call to Gemini, idle-waiting on the network) queues behind a `process_save` that
may be running `ffmpeg` and twelve sequential `tesseract` processes.

That produces **head-of-line blocking**, and the blocking job's duration is
bounded only by the sum of its timeouts:

| Path through `process_save` | Bound | Source |
|---|---|---|
| Link with captions (RapidAPI) | ~1–3s *(est.)* | 2 HTTP calls, `rapid-yt.timeout: 15s` |
| yt-dlp probe + captions | up to **150s** | `probe-timeout: 60s` + `caption-timeout: 90s` |
| ASR (audio → Groq) | up to **150s** | `audio-timeout: 120s` + `groq.timeout: 30s` |
| **OCR (video → frames → tesseract)** | up to **540s** | `video-timeout: 180s` + `ffmpeg 60s` + 12 frames × `ocr.timeout: 30s` |

**A single OCR save can hold the only worker slot for up to nine minutes**, during
which every other user's save — including saves needing nothing but a 3-second
Gemini call — sits `queued`. That is the reported symptom exactly: *"long-running
extraction blocks other requests."*

The 0.1 CPU allocation makes it worse, not better. Render's free instance is
**0.1 CPU and 512 MB** ([Render docs](https://render.com/docs/free), checked
2026-08-15) — tesseract and ffmpeg are CPU-bound, so the wall-clock cost of the
OCR path on this host is at the pessimistic end of that range, not the middle.

### What is *not* the bottleneck

Worth stating so effort doesn't go to the wrong place:

- **The HTTP layer is fine.** `spring.threads.virtual.enabled: true`, so request
  threads are not a scarce resource.
- **The queue is fine.** `jobs_claim_idx (status, run_after, priority desc)`
  covers the claim query; the poller only polls when idle (it blocks on
  `slots.acquire()` while a job runs), so it is ~43k lightweight queries/day at
  worst, not a load problem.
- **Job chaining is fine.** Each handler enqueues its successor *before*
  returning, and `store.succeed()` runs before `slots.release()`, so a chained
  job is claimed immediately rather than waiting a 2s poll interval. The 2s only
  applies to an empty queue.

---

## 2. Root cause, and five constraints behind it

Classified as the brief asks:

| # | Constraint | Class | Binding when | Severity |
|---|---|---|---|---|
| **1** | One worker slot shared by cheap and expensive jobs | **connection/thread-bound** | Any burst | **Now** |
| **2** | Gemini 500 RPD primary / 20 RPD fallback | **AI rate-limit-bound** | >500 saves/day | Daily ceiling |
| **3** | `save_stages` and `jobs` are never pruned | **database-bound** | ~1,200–2,200 image saves *(measured)* | Unbounded growth |
| **4** | HikariCP pool = 5, shared by HTTP *and* jobs | **connection-bound** | ~5 concurrent DB ops | Measured |
| **5** | No RPM limiter anywhere (Gemini is 15 RPM) | **AI rate-limit-bound** | Concurrency > 1 | On raising #1 |
| **6** | No content-level dedupe | **AI rate-limit-bound** | Multi-user | Wasted budget |

### #3 is the one nobody has flagged — but it is slower than it first looks

`ProcessSaveHandler.downloadAndStoreImage` base64-encodes an uploaded screenshot
and writes it into `save_stages.payload`, a JSONB column:

```java
String b64 = Base64.getEncoder().encodeToString(imageBytes);
stages.record(saveId, STAGE_IMAGE_READY, Map.of("image_b64", b64, ...));
```

**Nothing ever deletes it.** There is no `@Scheduled` anywhere in `api/`, no
`delete from save_stages`, and no `delete from jobs`. The row survives until the
save itself is deleted (`on delete cascade`).

> ⚠️ **This section originally estimated ~45 image saves to fill the database,
> reasoning from the 10 MB upload cap. That was wrong by a factor of ~45, and
> measuring it is what showed so.** The corrected figures below are measured
> against the live database on 2026-08-15, not derived.

**Measured** (live Supabase, read-only probe):

| Stage | Rows | Avg | Max | Total |
|---|---|---|---|---|
| `image_ready` | 11 | **223 kB** | 420 kB | 2,452 kB |
| `extracted` | 101 | 1,000 B | 11 kB | 99 kB |
| `classified` | 115 | 600 B | 1,810 B | 67 kB |
| `enriched` | 3 | 115 B | 115 B | 345 B |
| `accepted` | 1 | 64 B | 64 B | 64 B |

`jobs`: **522 rows** (489 `succeeded`, 33 `failed`).
**Whole database: 18 MB of 500 MB.**

The 10 MB worst case never materialises because `pg_column_size` reports the
*TOAST-compressed* size, and base64 is a 64-symbol alphabet that pglz compresses
back to roughly the original binary. A real phone screenshot lands at ~223 kB
stored, not 13 MB. So the actual runway is:

```
500 MB / 223 kB (avg)  ≈ 2,240 image saves
500 MB / 420 kB (max)  ≈ 1,190 image saves
```

**Revised conclusion.** This is unbounded growth against a fixed ceiling and it
must be fixed — but it is *months* of ordinary use away, not weeks, and it does
not outrank the concurrency work the way the first draft claimed. Retention is
still worth doing first only because it is small, safe and already done; the
image-bytes-to-Storage change (§10 #2) drops from urgent to merely correct.

### #5 will bite the moment #1 is fixed

`GeminiBudgetService` enforces requests-per-**day** only. The verified limits
(CLAUDE.md's own dashboard table, 2026-08-05) are **15 RPM** on
`gemini-3.1-flash-lite` and **5 RPM** on `gemini-3.6-flash`. Nothing in the
codebase enforces a per-minute rate.

At concurrency 1 this is masked: one classify call at a time cannot exceed 15/min.
Raise the fast lane to 4 and, at ~8s per call, the ceiling becomes ~30 calls/min —
double the limit. The failure mode is a 429 → `RetryableJobException` → **an
attempt spent** and a 30s backoff. Raising concurrency without an RPM limiter
converts queue delay into retry delay and burns the retry budget doing it.

---

## 3. Recommended architecture — lanes, not machines

The instinct is to add workers. **The correct move on a free tier is to stop
letting one class of work block another inside the process you already have.**

```
                       ┌──────────────────────────┐
   POST /v1/saves ────►│  API (virtual threads)   │──► 202 Accepted
                       │  validate · persist ·    │    (unchanged)
                       │  enqueue                 │
                       └────────────┬─────────────┘
                                    │
                       ┌────────────▼─────────────┐
                       │  jobs  (Postgres queue)  │
                       │  SKIP LOCKED · priority  │
                       └────────────┬─────────────┘
                                    │  claimed by lane
              ┌─────────────────────┴──────────────────────┐
              │                                            │
   ┌──────────▼───────────┐                    ┌───────────▼──────────┐
   │  FAST LANE  (n=4)    │                    │  HEAVY LANE  (n=1)   │
   │  network-bound       │                    │  CPU/RAM-bound       │
   │                      │                    │                      │
   │  classify_save       │                    │  process_save        │
   │  enrich_save         │                    │   └ yt-dlp/ffmpeg    │
   │  embed_save          │                    │   └ tesseract/ASR    │
   │  convert_to_list     │                    │                      │
   │  generate_digest     │                    │  ~1 job, up to 9 min │
   │  ~2-10s per job      │                    │                      │
   └──────────┬───────────┘                    └───────────┬──────────┘
              │                                            │
              │      ┌──────────────────────────┐          │
              └─────►│  Gemini RPM limiter      │◄─────────┘
                     │  15/min · 500/day        │
                     │  (the real bottleneck)   │
                     └──────────────────────────┘
```

**The rule: a lane is a cost class, not a job type.** Two things follow that a
single pool cannot give you.

- **An OCR save stops blocking a Gemini call.** They are in different lanes with
  different semaphores, so the nine-minute worst case affects only other heavy
  jobs.
- **The lanes can be sized by what actually constrains them.** The fast lane is
  four HTTPS calls waiting on a socket — cheap in CPU and memory, so 4 is safe on
  0.1 CPU. The heavy lane stays at 1 because ffmpeg and tesseract in 512 MB is
  exactly the OOM risk the current comment describes. **The existing comment is
  right about the heavy work and wrong to apply it to everything.**

One refinement worth making at the same time: `process_save` for a link that
resolves through RapidAPI captions does no CPU work at all — it is two HTTPS
calls. Classifying it as heavy wastes the fast lane. **Route `process_save` by
outcome**: attempt the cheap providers in the fast lane, and enqueue a separate
heavy job only when the cascade actually needs media. That keeps the common case
off the contended lane entirely.

### The capacity chain, with the constraint named

The brief asks the architecture to identify the lowest-capacity component and
protect it. It is **not** the server:

```
Users              unbounded
  ↓
API capacity       ~thousands (virtual threads)     ← not binding
  ↓
DB pool            5 concurrent                     ← binding on HTTP bursts
  ↓
Queue capacity     unbounded (Postgres rows)        ← not binding
  ↓
Worker capacity    1 slot  →  proposed 4 + 1        ← binding on burst latency
  ↓
Extraction         up to 540s/job on 0.1 CPU        ← binding, heavy lane only
  ↓
AI RPM             15/min  (UNENFORCED)             ← binding once workers > 1
  ↓
AI RPD             500/day                          ← ★ THE CEILING
  ↓
Database           500 MB, never pruned             ← ★ HARD WALL in weeks
```

**Two components are starred because both are absolute.** RPD caps useful daily
work no matter how many workers exist; the database wall stops everything. The
worker slot is a *latency* constraint, not a throughput one — see §8, where the
worker turns out to be idle ~91% of the day even at the AI ceiling.

---

## 4. Free deployment strategy

### The two findings that decide it

Both checked against Render's own documentation on 2026-08-15, and both eliminate
the obvious plan:

1. **Free instance hours are shared across the workspace, not per service.**
   750 hours/month, and a month is 744 hours. Keeping one service warm 24/7 (which
   `.github/workflows/keepwarm.yml` already does, pinging every 14 minutes against
   the 15-minute spin-down) consumes **99.2% of the entire monthly allowance**.
   A second always-on free service is arithmetically impossible.
2. **Background workers have no free instance type at all.** Only web services,
   static sites and certain databases can be Free. A dedicated worker service on
   Render starts at $7/mo.

**So "split the API and the worker into two free services" cannot be built as
stated** — not because of complexity, but because the free tier does not sell it.
This is precisely why the recommendation is lanes inside one process.

### Host comparison

`docs/extraction-architecture.md` Part E already contains a full evaluation
(dated 2026-08-14) that this section does not duplicate. Its conclusions stand and
are load-bearing here:

| Host | Verdict | Decisive reason |
|---|---|---|
| **Render free** | **Keep for API + lanes** | 512 MB / 0.1 CPU / 750 shared hours; already deployed and working |
| Hugging Face Spaces | **Rejected** | Docker Spaces require a paid plan; Static Spaces cannot run a process or hold a secret; **same datacenter IP, so it does not fix the YouTube block** |
| Cloudflare Workers | **Impossible** | 128 MB V8 isolate, 10 MB bundle cap, no filesystem or subprocess — an ffmpeg binary alone exceeds the bundle limit |
| Supabase Edge Functions | **Impossible for extraction** | Deno runtime, no ffmpeg/yt-dlp/tesseract, wall-clock limit far below the OCR path |
| **Fly.io** | **Recommended for the heavy lane, when paying** | Cheapest real container host: 1 GB ≈ $5.92/mo. No free tier for new orgs |
| GitHub Actions as a drain worker | **Not recommended** | 2,000 min/month on private repos ≈ 33 hours; 5-minute cron granularity; using CI as continuous compute is against the spirit of the terms |

**Nothing free can run the heavy lane except the container you already have.**
That is the finding that makes a multi-service free architecture a dead end, and
it is why §3 puts the effort into isolation rather than distribution.

### Recommended placement

| Component | Where | Cost |
|---|---|---|
| API + fast lane + heavy lane | Render free web service (existing) | **$0** |
| Queue | Supabase Postgres `jobs` (existing) | **$0** |
| Database | Supabase free, **+ pruning** (§10) | **$0** |
| Keep-warm | GitHub Actions cron (existing) | **$0** |
| Artifact storage | Supabase Storage (existing) | **$0** |
| *Heavy lane, when $6/mo is acceptable* | *Fly.io 1 GB — `extraction/` is already built* | *$5.92* |

**Total: $0/month.** No new service, no new account, no credit card.

---

## 5. Concurrency model

Every number below is derived from a constraint, not chosen for roundness.

| Lane | Concurrency | Why exactly this |
|---|---|---|
| HTTP | Unbounded (virtual threads) | Not a scarce resource; the DB pool is the real gate |
| **Fast lane** | **4** | Network-bound; 4 × ~2 MB heap is trivial in 512 MB. Ceiling is the **DB pool (5)**, not CPU — one connection must stay free for HTTP |
| **Heavy lane** | **1** | ffmpeg + tesseract on 0.1 CPU / 512 MB. **Unchanged** — the existing OOM reasoning is correct for this class |
| **Gemini calls** | **15/min, 500/day** | The provider's verified limits. Enforced with a token bucket, *not* by worker count |
| DB pool | 5 → **8** | Fast lane 4 + heavy 1 + poller 1 = 6 concurrent job connections; 5 would starve HTTP entirely. CLAUDE.md's own Supabase guidance is 5–10 |
| Per user | 1 job (unchanged) | `group_id` exclusion in `JobStore.claim` already prevents one user monopolising the pool |

**Do not raise the heavy lane to use the "spare" fast-lane capacity.** They are
sized against different resources: the fast lane is bounded by database
connections, the heavy lane by RAM and CPU. Those numbers are not fungible.

**The `group_id` exclusion becomes more valuable, not less.** At concurrency 1 it
barely mattered — nothing could run in parallel anyway. At fast-lane 4 it is what
stops one user's ten-save backlog occupying all four slots while another user's
single save waits.

---

## 6. Queue design

The existing schema needs **no migration for the lane split**. It already has
everything required:

```sql
jobs (
  id, type, payload jsonb, status, priority int,
  group_id,                          -- per-user fairness, already enforced
  idempotency_key text unique,       -- dedupe, already enforced
  attempts, max_attempts,
  run_after,                         -- backoff + RetryAfter, already used
  claimed_at, claimed_by, last_error, created_at, updated_at
)
create index jobs_claim_idx on jobs (status, run_after, priority desc);
```

The lane split is **one added predicate** on the existing claim query:

```sql
where status = 'queued'
  and run_after <= now()
  and type = any(:laneTypes)        -- ← the only change
  and (group_id is null or group_id not in (...))
order by priority desc, run_after, created_at
limit 1
for update skip locked
```

### `priority` is plumbed but never set — activate it

The column exists, the claim query orders by it, and **`JobQueue.enqueue`'s
priority parameter is never called with anything but 0**: every call site goes
through `enqueueForUser`, which hardcodes `0`. Confirmed by grep — there are no
explicit-priority enqueues in the codebase.

Turning it on is free and directly serves the brief's §8:

| Priority | Work | Rationale |
|---|---|---|
| **100** | `classify_save` for a save the user is currently viewing | The one case where a human is waiting |
| **50** | `process_save`, `classify_save` (normal) | Interactive default |
| **10** | `enrich_save`, `embed_save` | The save is already `ready` and searchable without these |
| **0** | `generate_digest`, `detect_duplicates` | Nobody is waiting |

This also fixes a real inversion: today a digest (nobody waiting) and a
freshly-shared Reel (someone watching a spinner) compete as equals in FIFO order.

### Failure recovery — already complete

The brief's required state machine is implemented. Recording it here so it isn't
rebuilt:

```
queued ──claim──► running ──success──► succeeded
   ▲                 │
   │                 ├─ RetryAfterException ──► queued  (no attempt spent) ★
   │                 ├─ PermanentJobException ─► failed  (+ save marked failed)
   │                 └─ any other exception ───► queued  (attempt spent, backoff)
   │                                              └─ at max_attempts ──► failed
   └──── requeueStale() ◄── claimed_at older than 15m (worker died, no attempt spent)
```

★ is the detail worth preserving: a quota rejection reschedules **without**
spending an attempt, which is why exhausting the daily Gemini budget parks saves
until tomorrow instead of failing five of them.

**Not implemented, and worth adding:** a dead-letter view. A `failed` job is
terminal and invisible — there is no endpoint or query that surfaces
`status = 'failed'` for an operator. See §10.

---

## 7. AI optimisation — the only lever that raises the daily ceiling

Worker concurrency does not increase daily capacity by a single save. **500 RPD is
500 classify calls, whatever the machine does.** Only these four change that number.

### 7.1 Content-level dedupe — the largest available win

`SaveService.create:136` flags this explicitly as unbuilt:

> ```java
> // Keyed on the save id, so a retried enqueue is a no-op. This does not
> // dedupe a re-shared URL - that is content-level and lands with the
> // share extension work.
> ```

Today, ten users saving the same viral Reel costs **ten** extractions and **ten**
Gemini requests — 2% of the entire day's budget for one piece of content.

```
                 CURRENT                          PROPOSED
  10 saves ──► 10 extractions              10 saves ──► 1 extraction
           ──► 10 Gemini calls                      ──► 1 Gemini call
           ──► 2% of daily budget                   ──► 0.2% of daily budget
                                                    ──► 10 rows referencing it
```

Implementation shape, reusing what exists:

- A `content_extractions` table keyed on `normalized_url_hash` holding
  `structured_data`, `knowledge_type`, `confidence` and an `extraction_version`.
- `process_save` checks it first. A hit **skips both extraction and classify**
  and copies the result onto the save.
- Normalisation must strip tracking parameters (`utm_*`, `igshid`, `si`, `fbclid`)
  and unify known-equivalent hosts, or `youtu.be/X` and
  `youtube.com/watch?v=X&si=…` hash differently and the cache never hits. The
  URL-shape work for this already exists in `RapidYtClient.extractVideoId`, which
  handles nine YouTube shapes.
- `extraction_version` is what allows a prompt or schema change to invalidate the
  cache deliberately rather than serving stale structure forever.

**Per-user privacy caveat, stated rather than glossed:** the cache stores what a
model extracted from *public* content at a URL, so sharing it across users leaks
nothing about who saved it. But a save's `structured_data` is copied into a row
the other user owns, so the cached record must hold **only** extraction output —
never `spaceId`, `userId`, notes, or anything a person added.

### 7.2 Skip the model when the answer is deterministic

Already partly done and worth extending. `SaveService.create` marks a `TEXT` note
`ready` immediately with no Gemini call at all — the right pattern. Extend it to:

- A URL whose extraction produced structured metadata sufficient on its own
  (a recipe site's JSON-LD, an OpenGraph article) — deterministic parse, no model.
- A `github_repo` URL — the GitHub API returns name, description, language and
  topics directly. Enrichment territory, not classification.

### 7.3 Cache the *confidence retry* decision

`ClassifySaveHandler` calls the fallback model when primary confidence is below
threshold. The fallback pool is **20 RPD** — it is exhausted by 20 low-confidence
saves in a day, and both consumers (the confidence retry *and* OCR vision
escalation) draw on the same 20. Under load these will collide silently.

**Recommendation:** meter them separately, or make vision escalation
budget-aware enough to log when it is skipped for lack of budget. Today both fail
closed with no user-visible symptom — the exact shape of the `gemini-2.5-flash`
404 that went unnoticed for weeks.

### 7.4 What not to do

- **Do not batch classify calls.** Combining four saves into one request halves
  request count but couples four users' saves into one failure and one retry, and
  a partial parse failure loses all four. The brief asks to batch "only where it
  genuinely improves throughput" — here it trades a scarce-but-recoverable
  resource for correctness.
- **Do not add a second Gemini call per save.** Standing architectural rule.
- **Embeddings are already exempt** from the budget guard, deliberately —
  separate pool (1000 RPD / 100 RPM), so counting them would throttle saves for
  nothing.

---

## 8. Capacity estimate

**Assumptions, all labelled.** Job durations are derived from configured timeouts
and expected network latency, **not measured in production**. Happy path =
`process_save` 3s *(est.)* + `classify_save` 8s *(est.)* + `enrich_save` 2s
*(est.)* + `embed_save` 2s *(est.)* ≈ **15s of work per save**. Heavy path (OCR)
≈ **240s** *(est.)*, dominated by `process_save`.

### Current — concurrency 1, all types share one slot

| Scenario | Time until the last save is `ready` | Note |
|---|---|---|
| 1 save | ~15s | Fine |
| 10 concurrent | ~150s (2.5 min) | Strictly serial |
| 50 concurrent | ~750s (12.5 min) | |
| 100 concurrent | ~1,500s (25 min) | |
| **+1 OCR save in the batch** | **+up to 9 min for everything behind it** | The reported symptom |
| Sustained throughput | ~4 saves/min happy path; ~0.25/min OCR | |
| **Daily ceiling** | **500 saves** | Gemini RPD, not the worker |

### Proposed — fast lane 4, heavy lane 1, RPM limiter

| Scenario | Time until last `ready` | Change |
|---|---|---|
| 1 save | ~15s | Unchanged (correctly — nothing to parallelise) |
| 10 concurrent | ~40s | **3.7× better** |
| 50 concurrent | ~200s (3.3 min) | **3.7× better** |
| 100 concurrent | ~400s (6.7 min) | **3.7× better** |
| **+1 OCR save** | **no effect on the other lane** | **The actual fix** |
| Sustained throughput | ~15 saves/min, **RPM-capped** | |
| **Daily ceiling** | **500 saves** — or **~625 effective** with 20% dedupe hit rate | Only dedupe moves this |

### The finding that reframes the whole exercise

At the 500 RPD ceiling and 15s per save, total worker time needed per day is:

```
500 saves × 15s = 7,500s = 2.1 hours of a 24-hour day
```

**The worker is idle ~91% of the time even at maximum daily AI capacity.** So:

> Concurrency is a **burst-latency** problem, not a throughput problem. The queue
> already has 10× the daily capacity the AI budget can pay for. Lanes are worth
> building because a user watching a spinner behind someone else's OCR job is a
> real and frequent experience — **not** because the system needs more saves/day.

This also means **do not pay for more compute.** Paying $6/mo for the Fly.io split
buys burst latency and OOM headroom; it buys exactly zero additional daily saves.
The only spending that raises daily capacity is a paid Gemini tier.

### AI rate-limit exhaustion

Already handled correctly. At 500 primary + 20 fallback, `GeminiBudgetService`
throws `RetryAfterException` with a delay computed to the next US/Pacific
midnight, jobs park without spending attempts, and the app shows them as
`processing`. **The gap is user-facing**: nothing tells the user "this will be
processed tomorrow" — the save simply sits. Worth a distinct save status.

---

## 9. Cost

| Item | Plan | Monthly |
|---|---|---|
| Render web service | Free (512 MB, 0.1 CPU, 744/750 hrs used by keep-warm) | **$0.00** |
| Supabase (Postgres + Auth + Storage) | Free (500 MB DB, 1 GB storage) | **$0.00** |
| Gemini | Free tier (500 + 20 RPD, 1000 embed RPD) | **$0.00** |
| Groq (ASR) | Free tier | **$0.00** |
| RapidAPI YouTube | Free tier | **$0.00** |
| GitHub Actions (keep-warm + deploy) | Free | **$0.00** |
| **Total (recommended architecture)** | | **$0.00** |
| *Optional: Fly.io 1 GB heavy lane* | *paid, no free tier for new orgs* | *$5.92* |

**Nothing in the recommended architecture costs money.** The changes are
configuration and code inside services already running.

---

## 10. Implementation changes

Ordered by value per unit of risk. **1 and 2 are urgent and independent of
everything else.**

**Items 1–6 and 8 all shipped on 2026-08-15.** Status below is what is in `main`,
not a plan.

| # | Change | Files | Status |
|---|---|---|---|
| **1** | **Prune `save_stages` + `jobs`** | `job/RetentionSweeper`, `job/RetentionProperties`, `WeavrApiApplication` | ✅ **Shipped** |
| **2** | **Lane split** — one poller + pool per cost class | `job/JobLaneProperties`, `JobRunner`, `JobStore.claim`, `JobProperties`, `application.yml`, `render.yaml` | ✅ **Shipped** |
| **3** | **Stop storing image bytes in Postgres** | `ProcessSaveHandler.downloadAndStoreImage`, `ClassifySaveHandler.resolveImageBytes` | ✅ **Shipped** |
| **4** | **Gemini RPM token bucket** — 15/min primary, 5/min fallback | `gemini/ModelRateLimiter`, `GeminiBudgetService`, `GeminiProperties` | ✅ **Shipped** |
| **5** | **Activate `priority`** | `job/JobPriority`, `JobQueue`, `EnrichSaveHandler`, `EmbedSaveHandler`, `DigestController` | ✅ **Shipped** |
| **6** | **Raise DB pool** — 5 → **10**, sized against lane concurrency rather than picked | `render.yaml` | ✅ **Shipped** |
| **8** | **Queue visibility** — periodic log line + health contributor | `job/JobQueueHealthIndicator` | ✅ **Shipped** |
| 7 | **Content dedupe by normalised URL hash** | new `content_extractions` table, `ProcessSaveHandler` | **Deferred** — descoped by request |
| 9 | **`budget_exhausted` save status** | `SaveStatus`, `ClassifySaveHandler`, app | Not done — note `SaveStatus.PENDING` already exists for this and is unused |
| 10 | *Wire `extraction/` behind a flag* — `extraction-architecture.md` Phase 5 | `SourceExtractor` impl | Not done (separate doc's phase) |

### What shipped, in more detail

- **Lanes are configured data, not two hardcoded pools.** `weavr.jobs.lanes.*`
  is a map of name → `{concurrency, types, fallback}`, so a third lane is config.
  An unconfigured deployment collapses to exactly the old behaviour — one pool at
  `weavr.jobs.concurrency` claiming every type — because `render.yaml` had been
  setting that variable since before lanes existed and a silently-ignored config
  value is a bad way to find out.
- **A job type no lane claims is swept into the fallback lane, loudly.** The
  failure it prevents is silent and total: a registered handler whose type nobody
  polls for leaves those saves `queued` forever with nothing in the logs.
- **The RPM limiter hands the job back rather than blocking.** Past
  `rate-limit-max-wait` it throws `RetryAfterException`, which costs no attempt,
  instead of holding one of a small number of worker slots on a sleep.
- **`GeminiBudgetService.acquire` is no longer `@Transactional`.** It never needed
  to be — the guard is entirely inside one atomic `UPDATE ... WHERE requests_used
  < limit` — and dropping it is what lets the rate limiter wait without holding a
  pooled connection.
- **Image bytes are no longer copied into Postgres at all.** They were being
  copied *from* Supabase Storage *into* the database beside it, for a value read
  once seconds later. `save_stages` now holds a breadcrumb (`image_url`,
  `mime_type`, `size_bytes`) and classify re-fetches. Both shapes are read
  permanently — there is no reprocess path, so pre-2026-08-15 `image_b64` rows
  never age out.

**Client-side (§16 of the brief): no work needed.** The app is already
local-first with optimistic writes, a durable outbox, a 2s poll for `processing`
saves, per-save `failed` states and a working Retry path
(`docs/local-first.md`, `SavesProvider`, `app/src/saves/retry.ts`). The only
addition worth making is surfacing #9's "queued until tomorrow" distinctly from
ordinary processing.

---

## 11. Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Database hits 500 MB | **Low** — months (measured: 18 MB used, ~2,200 image saves of runway) | Total outage of all writes | **#1 shipped**; #3 removes the growth at source |
| Fast lane 4 exceeds 15 RPM → 429 storms | **High** if #3 ships without #4 | Attempts burned, saves fail | **Ship #4 with #3, not after** |
| DB pool starvation (jobs vs HTTP) | Medium | HTTP 500s under load (already seen at pool=3) | #6, and keep fast lane ≤ pool−2 |
| 512 MB OOM from raising concurrency | Low if heavy lane stays 1 | Instance restart, jobs requeued as stale | **Do not raise the heavy lane** |
| Render cold start (30–60s) | Medium | First save after idle is slow | Keep-warm exists; a deploy or OOM still restarts |
| Keep-warm silently stops | Medium | Cold starts return | GitHub disables schedules after 60 days repo inactivity; `*/14` leaves ~1 min margin |
| **750 instance-hours exhausted** | **Medium** | **Service stops for the rest of the month** | 744/750 used by keep-warm alone — **a second free service or a redeploy loop overruns it** |
| YouTube datacenter-IP block | **Confirmed, live** | YouTube saves fail without RapidAPI | `RapidYtClient` covers it; no host change fixes it |
| Dedupe serves stale structure | Low | Wrong extraction reused | `extraction_version` in the cache key |
| Gemini free-tier terms | — | Prompts used for model improvement | Already disclosed in `legal/privacy.html` |

---

## 12. Migration plan

Each step is independently deployable and reversible. **No step requires
draining the queue or pausing saves.**

**Step 1 — Retention (no behaviour change). ✅ SHIPPED 2026-08-15.**
`RetentionSweeper` deletes `save_stages` rows for saves already `ready` or
`failed` and older than 7 days, `succeeded` jobs older than 7 days, and `failed`
jobs older than 30 days. Bounded batches, each its own transaction, on a fixed
delay rather than a cron (a free instance restarts too often for a nightly cron
to be reliable). `pending` is deliberately excluded — see the class comment.
*Verified:* 9 unit tests; app boots with the new schedule; and the real SQL was
executed against the live schema inside a transaction and **rolled back** —
it would delete 92 stage rows and 278 succeeded jobs today, while correctly
leaving the 1 stage row still held by a non-terminal save untouched.
*Rollback:* `WEAVR_RETENTION_ENABLED=false`.

**Step 2 — Image bytes out of Postgres.** *(Demoted — see §2. Correct, not urgent.)*
Write image bytes to the existing Supabase Storage bucket and store the object
path in `save_stages` instead of base64. `ClassifySaveHandler` fetches by path.
**Back-compat is required, not optional:** existing rows hold `image_b64`, so the
reader must accept both shapes permanently — there is no reprocess path in this
codebase. *Verify:* a real screenshot save end-to-end, as
`docs/testing.md` describes.

**Step 3 — Lane split + RPM limiter, together. ✅ SHIPPED 2026-08-15.**
Both landed in one change, per §11 — shipping the lanes without the limiter would
have traded queue delay for 429s. Fast lane 4, heavy lane 1, both overridable by
environment variable (`WEAVR_JOBS_FAST_CONCURRENCY` /
`WEAVR_JOBS_HEAVY_CONCURRENCY`) so a rollback is a variable change, not a
redeploy.
*Verified:* 13 new unit tests; the app boots with both lanes reporting the
correct types and concurrencies; the lane claim query and the queue-health query
were both executed against the live schema (the claim one inside a transaction,
rolled back) — **which is how the health query's `FILTER`-on-a-non-aggregate
syntax error was caught**, since no mocked test runs SQL.
*Still unverified:* the actual anti-blocking behaviour — that a link save
finishes while an OCR job runs — has **not** been observed, because it needs two
real saves of different cost classes in flight at once. That is the one claim in
this section resting on reasoning rather than measurement.

**Step 4 — Priority.**
Set priorities at each call site. Purely additive: unset priorities are already
`0`, and the claim query already orders by it.

**Step 5 — Dedupe (the only real migration).**
Create `content_extractions` empty. Write-through first — populate on every
extraction but **do not read from it** — for long enough to inspect real hash
collisions and normalisation behaviour against live data. Only then enable reads,
behind a flag. This is the K0 pattern from `docs/knowledge-collections.md`:
measure the spec against real rows before trusting it.

**Existing saves are unaffected at every step.** Nothing here changes
`structured_data`, the `saves` schema, or any client-visible contract.

---

## Summary

1. **The async architecture the brief asks for already exists.** The queue, the
   idempotency, the retries, the stale reaper and the durable AI budget are all
   built and working.
2. **Concurrent saves degrade because one worker slot serves both 3-second Gemini
   calls and 9-minute OCR jobs.** Fixing that is a lane split inside the existing
   process — not more servers.
3. **A multi-service free architecture is not buildable on Render**: free instance
   hours are shared across the workspace (744 of 750 already consumed by
   keep-warm), and background workers have no free tier at all. Hugging Face is
   rejected for concrete documented reasons, including that it shares the same
   datacenter-IP block.
4. **`save_stages` and `jobs` were never pruned** — unbounded growth against a
   500 MB ceiling. **Now fixed** (`RetentionSweeper`). The first draft of this
   document called it weeks away and the top priority; measuring it against the
   live database showed ~2,200 image saves of runway, not ~45, because TOAST
   compresses base64 far better than the 10 MB upload cap suggests. **Measuring
   before prioritising changed the order of the plan** — the lane split is the
   top item, not this.
5. **Concurrency buys latency, never daily capacity.** At the 500 RPD ceiling the
   worker is idle 91% of the day. Only deduplication raises the number of saves
   the system can actually process.
6. **Total cost: $0/month.**

## Sources

- [Deploy for Free – Render Docs](https://render.com/docs/free)
- [Background Workers – Render Docs](https://render.com/docs/background-workers)
- [Render Free Tier 2026: 750 Hours, Redis, Cron Jobs](https://unanswered.io/guide/render-free-tier-details)
- [Render Pricing Guide: Free Tier, Compute and Database Costs](https://kuberns.com/blogs/render-pricing/)
- [Platforms with a real free tier for developers in 2026](https://render.com/articles/platforms-with-a-real-free-tier-for-developers-in-2026)
- In-repo, previously verified: [`docs/extraction-architecture.md`](extraction-architecture.md) Part E (host comparison, Hugging Face verdict, Fly.io pricing — dated 2026-08-14)
