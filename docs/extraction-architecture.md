# Extraction architecture

Investigation of three reference YouTube extractors against our own cascade, the failure points it surfaced in our code, a recommended service split, and a hosting verdict.

**Status: investigation, plus Phases 1 and 2 of Part F below.** Dated 2026-08-14; Phases 1 and 2 landed 2026-08-15 — see [CLAUDE.md](../CLAUDE.md) for the verified details. Phase 3 onward, and the dedicated extraction service in Part D, remain investigation only.

---

## The reframe, before anything else

**None of the three repositories is more reliable than our extractor. Two are measurably less so.**

- FreeTube has no retry and no timeout around its YouTube calls at all.
- pytube ships with retries defaulting to zero and has not had a code push since 2024-08-15.
- YoutubeExplode retries five times with no backoff and no jitter.

Our job queue's exponential backoff, bounded subprocess supervision (`ExternalProcess`) and permanent-vs-retryable classification (`YtDlpErrors`) are better engineering than anything in the three.

What they have that we do not is **situation, not sophistication**. All three are desktop applications running on end-user machines from residential IP addresses, speaking YouTube's InnerTube API as attested first-party clients. Our backend runs from a Render datacenter IP in Oregon. That difference is the entire YouTube failure, and it is the one property that does not transfer to any backend on any host — including Hugging Face.

So the recommendation is not "adopt their extractor." It is: fix the genuinely broken things in ours, split the media work out for memory and replaceability, and keep buying YouTube access from a provider rather than trying to manufacture it.

---

## A. Repository findings

All three bypass `yt-dlp` entirely and call YouTube's private InnerTube JSON API directly. None uses browser automation for extraction. None uses the official YouTube Data API. Versions and paths below were read from the repositories, not recalled.

### FreeTube — protocol-native, thin reliability

| | |
|---|---|
| Tech | `youtubei.js ^17.2.0` — InnerTube client |
| Deps | `googlevideo ^4.1.1`, `bgutils-js ^4.0.3`, `shaka-player ^5.1.12` |
| URL/ID | `helpers/utils.js → getVideoParamsFromUrl()` |
| Shorts | Explicit `/shorts/` path test; feed items branch on InnerTube's own `ReelItem` / `ShortsLockupView` types |
| Streams | `helpers/api/local.js → decipherFormats()` |
| Flow | URL → `createInnertube()` → player response → decipher → hand to shaka-player |
| Retries | None on InnerTube calls |
| Timeouts | None on InnerTube calls; 15 s on the Invidious instance-list fetch only |
| Cookies | No account auth at all; tracking cookies actively stripped |
| Proxy | Session-wide SOCKS5/HTTP, user-configured in settings |
| Deploy | Electron desktop app, user's machine |

Format selection is delegated wholesale to shaka-player's adaptive bitrate rather than implemented. Never writes media to disk — it streams for playback, so there is no download, temp-file or cleanup story to compare against.

### YoutubeExplode — best engineering of the three

| | |
|---|---|
| Tech | `YoutubeExplode 6.6.1` + `.Converter 6.6.1`, direct InnerTube over `HttpClient` |
| Role | YoutubeDownloader is a thin UI shell; all extraction is in the library |
| URL/ID | `Videos/VideoId.cs → TryNormalize`, six regexes |
| Shorts | One regex, then no special path — a Short is just a `VideoId` |
| Streams | `Videos/Streams/StreamClient.cs` — itags plus DASH manifest |
| Flow | URL → `VideoId` → `/youtubei/v1/player` → cipher manifest → stream URLs → download → FFmpeg mux |
| Retries | 5× at three layers: transport 5xx, manifest fetch, watch-page parse. No backoff, no jitter, no Polly |
| Timeouts | Not configured — inherits `HttpClient`'s 100 s default |
| Cookies | Opt-in, documented, user's own login, encrypted at rest |
| Temp files | `.stream-{i}.tmp`, disposed in a `finally` around the whole conversion |
| Deploy | WPF/Avalonia desktop app, user's machine |

Two details are genuinely worth having: it re-validates content length by requesting the stream's last byte because YouTube reports it wrongly (issue #759), and it tolerates DASH-manifest 404s rather than failing extraction (issue #728). Both are measured bug fixes, not speculative hardening.

### pytube — cautionary tale, do not adopt

| | |
|---|---|
| Tech | Pure Python, no external binaries |
| URL/ID | `extract.py → video_id()`, one generic 11-character regex |
| Shorts | No handling. Matches only incidentally, because the regex needs just a preceding slash |
| Streams | `streams.py`, `itags.py`, `query.py`; cipher work in `cipher.py` |
| Flow | URL → watch page + InnerTube player → `streamingData` → signature transform → download |
| Retries | Loop exists, but `max_retries` defaults to **0** |
| Timeouts | OS socket default unless the caller overrides |
| Cookies | None. OAuth device-code login exists for the user's own account |
| Temp files | None — writes straight to the target path, no cleanup step |
| Deploy | Library, imported into whatever the user builds |

**Last code push 2024-08-15. 766 open issues.** Issue #2167 (2026-04) is titled "Pytube is no longer maintained. Time to migrate to PYTUBEFIX." Forty issues match a search for `HTTP Error 400: Bad Request`, the signature of a stale player payload. The maintained fork `pytubefix` pushed 2026-08-09 with 16 open issues.

### Reliability techniques, side by side

| Technique | FreeTube | YoutubeExplode | pytube | Weavr today |
|---|---|---|---|---|
| Retry with backoff | none | 5×, no backoff | default 0 | **exponential, capped** |
| Request timeouts | none | 100 s default | OS default | **none — F1** |
| Error classification | no | no | no | **permanent vs retryable** |
| Temp-file cleanup | n/a | finally-disposed | none | **finally-deleted** |
| Subprocess supervision | n/a | CliWrap | n/a | **bounded, dual-drained** |
| Response caching | session only | cipher only | filesize only | **none — F9** |
| Runs from | residential IP | residential IP | caller's choice | **datacenter IP** |

---

## B. What we should copy

Short list, deliberately. Most of what makes these projects work is either already ours or not transferable.

- **Validate what the provider tells you.** YoutubeExplode re-checks content length by requesting the last byte because YouTube's reported value is sometimes wrong. The generalisation: treat provider-supplied lengths and durations as claims to bound against, not facts to trust — the same instinct as our own "read the output directory before trusting yt-dlp's exit code."
- **Tolerate partial failure inside a source.** A missing DASH manifest does not fail their extraction. Our caption path already does this well; the provider path does not, and should.
- **Layered retries at the transport boundary, not just the job boundary.** Their 5× on 5xx is crude, but the placement is right: a transient socket error should not travel all the way up to the job queue and cost thirty seconds of backoff. Add the layer, with the backoff and jitter they lack.
- **Explicit URL normalisation with a named list of accepted shapes.** Six regexes in one function returning a typed `VideoId` is better than our single inline pattern, because the accepted set is legible and testable. Ours is missing three shapes as a direct result (F8).
- **Deterministic temp-file naming plus disposal in a `finally` wrapping the whole operation.** We already do this. Keep doing it in any new service.

---

## C. What we should not copy

### Client impersonation and attestation solving — flagged, not recommended

Both maintained repositories carry a load-bearing answer to YouTube's Proof-of-Origin requirement, and in both it is always-on rather than opt-in or documented as risky.

- FreeTube ships `bgutils-js` as a first-class dependency and runs the BotGuard challenge in an isolated offscreen view to mint a PO token, because InnerTube requests are otherwise rejected outright.
- YoutubeExplode instead impersonates an `ANDROID_VR` client, with a source comment citing its issue #933 (closed 2026-01) to the effect that this client still works without a PO token. A second path impersonates an embedded-TV player for age-restricted videos.

**We already ran the weaker version of the second technique and it failed.** `--extractor-args youtube:player_client=tv,android,web` was tested against eight fresh YouTube URLs on the live deploy with cookies also present and the command line confirmed correct in the logs; all eight returned the identical bot-check error. That is the evidence that the datacenter IP is the signal being acted on regardless of which client is claimed. Escalating further along this axis is both outside what we should implement and, on our own measurements, the losing engineering bet.

### The rest

- **Reimplementing InnerTube ourselves.** YoutubeExplode's issue history shows 403-shaped breakage every one to three months since 2023, each fixed by re-reverse-engineering. That is a permanent maintenance tax on a two-person team, to replace `yt-dlp` — which has the largest community doing exactly that work and is strictly the most robust of the four extractors here.
- **Hardcoded client version strings and API keys.** YoutubeExplode carries magic constants like a pinned client version; they are precisely what breaks the moment YouTube revs a client.
- **pytube, and anything modelled on it.** Unmaintained, no Shorts handling, retries off by default, no cleanup. Even `pytubefix`, which is genuinely current, is a single-maintainer fork against a moving target.
- **Cookie replay from a datacenter.** We proved this one ourselves: a cookie exported on a residential machine and replayed from an unrelated cloud IP is itself a bot signal. It also means holding a real person's session credential on a server, which is a liability with no upside now that it is measured not to work.
- **Full itag tables and DASH adaptive-bitrate logic.** They serve playback at the best available quality; we serve OCR at the worst legible quality. Their complexity solves a problem we do not have.
- **A browser or Electron runtime in the extraction path.** FreeTube's offscreen-view technique needs a full browser. On a container sharing 512 MB across the JVM, ffmpeg and tesseract, that is not a tradeoff worth discussing.

---

## Our actual failure points

Ranked by what breaks a save today. The top two have nothing to do with YouTube, and one is a security bug rather than a reliability bug.

### F1 — No HTTP client anywhere has a timeout *(critical)*

Every `RestClient` — Gemini, Groq, RapidAPI, TMDB, Places, link and PDF extraction, the image download — is built from the injected builder with no request factory, so no connect or read timeout is ever set. `weavr.rapid-yt.timeout: 15s`, `weavr.gemini.timeout: 30s`, `weavr.groq.timeout: 30s` and `weavr.enrich.timeout: 10s` are dead configuration: a grep for `timeout()` finds only the three external-process callers (`FfmpegClient`, `TesseractClient`, `FrameExtractor`).

The blast radius is what makes it critical. `JobRunner` uses a fixed platform-thread pool sized by `WEAVR_JOBS_CONCURRENCY`, which is **1** on Render. One hung socket pins the only worker forever. The stale-claim reaper returns the job *row* to the queue after 15 minutes, but the thread never comes back, so the requeued job cannot be picked up either. The pipeline is dead until someone restarts the service, and nothing logs an error.

`ExternalProcess` gets this exactly right for subprocesses. The HTTP layer never received the same treatment.

### F2 — Server-side request forgery in the link extractor *(critical)*

`LinkExtractor.fetch` (`LinkExtractor.java:70`) passes a user-supplied URL straight into `http.get().uri(url)` with no host validation, no private-address rejection, no redirect limit and no scheme restriction. The fetched body is parsed to text and returned into the user's own save, where they can read it — so this is SSRF with a working read-back channel, not a blind one.

Reachable through the ordinary save path: any URL that `yt-dlp`'s probe rejects as unsupported falls through to this extractor by design (`ExtractionCascade.extractLink`). That includes internal addresses, cloud metadata endpoints, and our own `/actuator` surface on localhost. The browser-like User-Agent the client sets makes it more likely to be served, not less.

### F3 — Unbounded response reads on a 512 MB instance *(critical)*

Both `LinkExtractor.fetch` (`:72`) and `ProcessSaveHandler.downloadAndStoreImage` (`:166`) read the whole response with `body(byte[].class)` and no size limit. A large or deliberately slow response is an out-of-memory kill of the entire service, taking the API down with the pipeline. The image path then base64-encodes the result, inflating it by a third before it reaches the database.

### F4 — The provider probe cannot retry, and degrades into a guaranteed failure

`RapidYtClient.probe` (`:81`) wraps everything in `catch (Exception) → Optional.empty()`. A transient 429, a 503 and a genuinely missing video are indistinguishable, and all three fall through to `yt-dlp` — which on Render fails every YouTube URL with the bot check. One rate-limit blip therefore becomes a permanently failed save rather than a retry thirty seconds later.

**Fixed 2026-08-15** — see [CLAUDE.md](../CLAUDE.md). `probe` now retries up to twice (full jitter, exponential) on a connection failure, a 429 or a 5xx, carried by a new `RapidApiStatusException` that preserves the status code `onStatus` previously discarded; a 404 or any other 4xx returns empty immediately, no retry.

### F5 — The provider path only covers captions and metadata

When the provider returns thin metadata, `ExtractionCascade` calls `continueFromMetadata` (`:125`), whose ASR and visual tiers invoke `ytDlp.downloadAudio` (`AsrTranscriber.java:52`) and `ytDlp.downloadVideo` (`VisualTextExtractor.java:155`) against YouTube directly. Those are the blocked path. A captionless YouTube video with a thin description still fails on Render, which reads as "the provider fix did not work" when in fact it was never wired to cover those tiers.

**Decided 2026-08-15, fail-fast chosen over routing ASR/OCR through the provider** — see [CLAUDE.md](../CLAUDE.md) for the full reasoning. The RapidAPI response as consumed by `RapidYtClient` carries no audio/video download URL (only metadata + caption tracks), so "route ASR/OCR through the provider" would need unverified assumptions about fields nothing in this codebase reads today. When RapidAPI's own metadata and every caption track it offers are both too thin, the cascade now fails immediately with `youtube_no_usable_text` instead of falling through to a yt-dlp download that is confirmed dead on Render regardless.

### F6 — Provider caption selection is first-wins

`firstCaptionUrl` (`RapidYtClient.java:120`) takes whichever track the provider lists first, with no language preference and no manual-over-automatic preference. The `yt-dlp` path already learned this the hard way and ranks candidates by parsed prose length, precisely because an auto-generated track is several times larger while carrying less text. The provider path discards that knowledge.

**Fixed 2026-08-15** — `RapidYtClient` now tries up to 5 caption tracks and keeps whichever parses to the longest prose, mirroring `YtDlpClient.bestCaptions`.

### F7 — yt-dlp is frozen, despite the configuration saying otherwise

`application.yml` reasons explicitly that "for a two-month hackathon, update-on-start is usually right." The Dockerfile implements update-at-first-build-then-never: the `pipx install "yt-dlp>=2026.7.4"` layer (`Dockerfile:27`) sits above any `COPY` that changes, so it is cached indefinitely. Extractors rot on a one-to-three-month cadence — the YoutubeExplode issue history is direct evidence of the interval.

*(Not part of Phase 2 — Phase 2's own bullet list doesn't name F7; still open.)*

### F8 — URL coverage misses three real YouTube shapes

The `YT_ID` pattern (`RapidYtClient.java:52`) handles `watch?v=`, `youtu.be`, `/shorts/` and `/embed/`, but not `/live/`, `m.youtube.com` or `music.youtube.com`. Both maintained repositories cover `/live/`. A miss here silently routes the URL to the blocked path.

**Fixed 2026-08-15** — `/live/` added to the pattern. `m.youtube.com`/`music.youtube.com` needed no change: the match was never anchored to the string's start, so `youtube.com/watch?v=` already matched inside either subdomain's URL.

### F9 — No probe caching

The same URL saved twice costs two provider calls; two people in a Space saving the same Reel costs two. A short-lived cache keyed on the normalised video id directly extends the provider quota.

### F10 — `.env.example` has drifted

`WEAVR_RAPID_YT_API_KEY` is in `render.yaml` and `application.yml` but missing from the tracked template that CLAUDE.md designates as canonical. A fresh clone silently runs with the provider disabled — and from a datacenter IP that means every YouTube save fails, for a reason the developer has no pointer to.

---

## D. Recommended architecture

**Option B, with the object storage being the Supabase bucket we already run.** Option A is ruled out by the hosting verdict below, but the shape of B is right independently of where it runs: a dedicated extraction worker, private, reached only by the backend, writing artifacts to storage and returning references rather than bytes.

```text
┌────────────────────────┐        ┌──────────────────────────┐       ┌──────────────────┐
│    Weavr backend       │  POST  │   Extraction service     │       │  Provider API    │
│                        │───────▶│                          │──────▶│  (YouTube path)  │
│ auth · save creation   │        │ URL validation · SSRF     │       └──────────────────┘
│ job queue · retry state│◀───────│ provider / yt-dlp routing │       ┌──────────────────┐
│ Gemini · Groq          │  JSON  │ captions · metadata       │──────▶│  yt-dlp          │
│ classification         │        │ ffmpeg · tesseract OCR    │       │  (IG, TikTok, …) │
│ embedding · Postgres   │        │ temp-file cleanup         │       └──────────────────┘
└────────────────────────┘        └──────────────────────────┘
            ▲                                  │ writes artifact
            │                                  ▼
            │                     ┌──────────────────────────┐
            └─────────────────────│    Supabase Storage      │
              reads by signed ref │  audio · frames · thumb  │
                                  └──────────────────────────┘
```

The boundary is the media bytes: everything that touches them is on the service side; everything that touches a model or the database is on the backend side. That is what keeps the extractor replaceable — swapping it changes one HTTP client and nothing else.

Instagram is unaffected by the YouTube block: 7 real Reels cleared the ordinary yt-dlp probe on Render.

### Where the line sits

The brief leaves ASR and OCR ambiguous. The rule to use: **the extraction service owns everything that touches the media bytes; the backend owns everything that touches a model or the database.**

- ffmpeg downmixing and keyframe cutting, and tesseract OCR, move to the service — local, deterministic, memory-hungry, and they need the bytes in hand. Shipping frames over the network to OCR them would be absurd.
- The Groq Whisper call and every Gemini call stay in the backend, because they are AI calls with their own budgets, keys and rate-limit accounting. When the service cannot produce text and `options.audio` was set, it returns an audio artifact reference and `needsTranscription: true` rather than transcribing.
- The OCR quality gate stays in the service (a local threshold on tesseract's own confidence output), but vision escalation does not — a failed gate returns the sharpest frames as artifacts and lets the backend decide whether to spend a Flash request.

This keeps every AI budget, retry counter and user record on one side of the wire.

### The API

Request:

```http
POST /extract
Authorization: Bearer <service-token>
Idempotency-Key: <save-id>
Content-Type: application/json
```

```json
{
  "url": "https://...",
  "options": {
    "audio": false,
    "frames": false,
    "quality": "best",
    "maxDurationSeconds": 90
  }
}
```

Success:

```json
{
  "success": true,
  "result": {
    "source": "captions",
    "text": "...",
    "needsTranscription": false,
    "metadata": {
      "platform": "youtube",
      "sourceId": "dQw4w9WgXcQ",
      "title": "...",
      "description": "...",
      "uploader": "...",
      "durationSeconds": 212,
      "captionLanguages": ["en"],
      "pinnedComment": null
    },
    "artifacts": [
      {
        "kind": "thumbnail",
        "ref": "sig:...",
        "bytes": 48213,
        "expiresAt": "2026-08-14T13:20:00Z"
      }
    ]
  }
}
```

Three deliberate choices:

1. `text` comes back inline because it is what the pipeline actually wants and it is small. Media never does.
2. An artifact is an **opaque signed reference with an expiry**, never a bucket key or a filesystem path — the service's storage layout stays private, and a leaked response body expires.
3. `Idempotency-Key` carries the save id, so the backend's existing retry semantics do not produce duplicate extractions or duplicate artifacts.

### The error model

```json
{
  "success": false,
  "error": {
    "code": "TEMPORARY_PROVIDER_ERROR",
    "message": "That site is blocking Weavr right now. We'll try again.",
    "retryable": true
  }
}
```

`retryable` is decided by the service and obeyed by the backend, rather than each side guessing. This is the classification `YtDlpErrors` already performs, promoted to the wire so it survives the process boundary.

| Code | Retryable | HTTP | Maps from / raised when |
|---|---|---|---|
| `INVALID_URL` | no | 400 | Unparseable, or scheme is not http/https |
| `UNSUPPORTED_URL` | no | 422 | `unsupported_source` — no extractor, and not a readable page |
| `CONTENT_UNAVAILABLE` | no | 404 | `content_unavailable`, `content_private`, `age_restricted`, `content_paywalled` |
| `AUTH_REQUIRED` | no | 403 | Source demands a login we do not and will not hold |
| `EXTRACTION_FAILED` | yes | 502 | `extract_failed` — unknown extractor breakage, assumed transient |
| `TEMPORARY_PROVIDER_ERROR` | yes | 503 | `source_blocked` — provider 429/5xx, bot check, rate limit |
| `NETWORK_ERROR` | yes | 502 | `source_unreachable` — connection reset, DNS, unreachable |
| `TIMEOUT` | yes | 504 | Per-request or whole-extraction deadline exceeded |
| `STORAGE_ERROR` | yes | 502 | Artifact write to the bucket failed |
| `INTERNAL_ERROR` | yes | 500 | Anything unclassified — retryable by default, as ours already is |

Two rules belong in the service's tests rather than its documentation:

- **A partial extraction is never reported as success.** If captions were requested and the fetch failed, the response says so even when metadata came back fine — a save built on a title alone is a worse outcome than a retry.
- **`message` is user-safe prose with no internals.** No stack traces, no paths, no upstream URLs. With silent capture it is the only failure explanation a user ever sees.

### Retries, and who owns them

The risk with two tiers of retry is multiplication — three in-call attempts inside five job attempts is fifteen fetches of a video that was deleted. The division must be explicit:

- **In the service:** at most 2 retries, only for a connection reset, a 5xx or a 429, with exponential backoff and jitter, and a hard whole-request deadline bounding all attempts together. Never retries a 4xx other than 429.
- **In the backend:** unchanged. The existing exponential job backoff handles everything the service reports as `retryable`. A `TEMPORARY_PROVIDER_ERROR` carrying `Retry-After` should become `RetryAfterException`, so a quota rejection costs no attempt — the mechanism already exists and is exactly right for this.
- **Never:** unbounded loops, retrying a non-retryable code, or retrying past the extraction deadline. Cleanup runs in a `finally` on every path, including the deadline path.

### Security

The service must never become an open URL fetcher. F2 shows we have already shipped a smaller version of that bug.

- **Authentication** — a shared bearer secret between backend and service, rejected with 401 when absent, with no anonymous path. Deploy as a private service with no public ingress so the secret is defence in depth rather than the only control.
- **SSRF defence, applied after DNS resolution and again after every redirect** — reject non-http(s) schemes, and reject any resolved address that is loopback, link-local, private, unique-local, multicast or unspecified. Resolving first and connecting to the resolved address is what closes the rebinding window; validating the hostname alone does not.
- **Platform allow-list** for the media path, so `yt-dlp` is only ever pointed at hosts we intend. The readable-page path is necessarily broader, which is exactly why it needs the address checks above and a redirect cap.
- **Limits, all enforced** — request body size, download size ceiling with a streaming abort rather than a post-hoc check, per-request deadline, whole-extraction deadline, and a concurrency semaphore sized to the container's memory.
- **Rate limiting** per calling identity, so a runaway backend loop cannot exhaust the provider quota or the disk.
- **Structured logging** with the save id and outcome code, and never the token, the provider key, a signed reference or a temp path.

---

## E. Hugging Face verdict

### NOT RECOMMENDED

Verified against the current Spaces documentation rather than assumed.

| Dimension | What Spaces gives | Fit |
|---|---|---|
| CPU | 2 vCPU | Adequate |
| RAM | 16 GB | **Genuinely good** — 32× Render free, and memory is our real constraint |
| Disk | 50 GB, not persistent | Fine — we delete everything in a `finally` anyway |
| Networking | Outbound on 80, 443, 8080 only | Fine for HTTPS extraction |
| Sleep | Free hardware sleeps; paid hardware runs indefinitely | Moot — Docker means paid, and paid does not sleep |
| Cold start | Restart on wake, deploy or eviction | Reducible by keep-warm, not eliminated |
| Deployment model | Git push rebuilds the Space | Workable |
| Docker vs Gradio | Both are compute Spaces | **Both now require a paid plan** |
| Static SDK | Free, but serves files only — no server-side process | **Cannot run anything** — see below |
| Free tier | Static free; 2 ZeroGPU *Gradio* Spaces for eligible personal accounts | A GPU-demo lane, not an HTTP service lane |
| Production posture | Platform for ML demos | No uptime commitment for a pipeline dependency |
| The YouTube block | Cloud datacenter IP | **Identical to Render — fixes nothing** |

**The free Static SDK is not a cheaper version of what we need — it is a different product.** A Static Space serves files: no Python, no subprocesses, no `yt-dlp`, `ffmpeg` or `tesseract`, and nothing that can accept `POST /extract`. It also cannot hold a secret, because it has no server to hold one on: the docs state that for Static Spaces both variables and secrets are exposed through client-side JavaScript in `window.huggingface.variables`, so the provider API key would be readable by anyone opening the page. The Space creation UI states the split plainly — "Gradio and Docker Spaces require a paid plan. Static Spaces stay free for everyone."

**Sleep is not one of the reasons, and should not be counted as one.** We already run a keep-warm cron — `.github/workflows/keepwarm.yml` pings the health endpoint every 14 minutes against Render free's 15-minute spin-down — so the pattern is proven here. But on HF it is not even needed: the docs make sleep a free-hardware behaviour and direct anyone wanting a Space to run indefinitely to paid hardware, which Docker already requires.

Two things keep-warm does not fix anywhere, worth stating since the same reasoning applies on our current host. It reduces cold starts rather than eliminating them — a deploy, an eviction or an OOM restarts the service regardless, and with silent capture a save can arrive during that window with nobody watching. And the mechanism is more fragile than it looks: GitHub Actions disables scheduled workflows after 60 days of repository inactivity, and cron triggers are best-effort and can be delayed under load, which leaves about a minute of margin at `*/14`.

**The decisive point is the last row, not the pricing one.** If Spaces were free and always-on it would still be a datacenter IP, which is the signal YouTube acts on, as our own eight-URL test established. Moving there changes which datacenter we are blocked from.

The pricing finding then removes the consolation prize: since Docker Spaces need a paid plan anyway, the comparison is no longer "free HF versus paid Render" but paid against paid — and at that point the platform built for always-on HTTP services with private networking wins over the one built for demos. **Deploy the extraction service as a private service on the platform the backend already runs on.**

The one thing Spaces genuinely offers is 16 GB of RAM, and that is worth naming because memory *is* our binding constraint — it is why job concurrency is pinned at 1. But the direct answer to a memory constraint is an instance with more memory, not a second platform with no uptime commitment and the same IP problem.

### Other hosts evaluated

Checked 2026-08-14. **Every one of these is a datacenter IP, so none of them changes the YouTube position** — this is a comparison on memory, cost and operability only.

| Host | Verdict | Detail |
|---|---|---|
| **Fly.io** | **Recommended** | No free tier for new orgs, but the cheapest real container host: 256 MB $2.02/mo, 512 MB $3.32, 1 GB $5.92, 2 GB $11.11. Free in-region private networking, auto stop/start machines suit a bursty workload |
| Railway | Works, no edge | No free tier since July 2023. Hobby is a **$5/mo minimum spend**, not a flat rate, with CPU, memory, volumes and egress on top |
| Cloudflare Containers | Plausible, unverified | Real `linux/amd64` containers, sleeps after 10 min idle, cold start "often 1–3 s" (for small images; ours is not). Instance sizes, pricing and required plan not confirmed |
| Cloudflare Workers | **Impossible** | V8 isolate. **128 MB memory on paid as well as free**, and a 10 MB compressed / 64 MB uncompressed bundle ceiling — an `ffmpeg` binary alone exceeds the bundle limit, and there is no filesystem or subprocess API to invoke it with. Unlimited HTTP wall time does not help when the work is native binaries |

**This revises the "stay on the existing platform" instinct.** That call was made when the comparison was paid-versus-paid with no differentiator. Fly at $5.92 for 1 GB — or $11.11 for 2 GB — is a real differentiator, because memory is the constraint that pins job concurrency at 1 today. The cost of the split (a second deploy pipeline, cross-platform networking rather than internal) is worth paying for 2–4× the memory.

---

## F. Implementation plan

Ordered so each phase is independently shippable and the riskiest work is de-risked by the cheapest. **Phases 1 and 2 are worth doing whether or not the service is ever built** — they are in-place fixes to bugs that are live now.

### Phase 1 — Stop the bleeding (in place, no new service)

**Landed 2026-08-15 — see [CLAUDE.md](../CLAUDE.md) for the verified writeup (full backend suite 478/478, 11 new tests).**

- ~~Configure a request factory with connect and read timeouts on every `RestClient`, wired to the config values that already exist and are currently ignored (F1).~~ Done via a new `RestClientConfig` (per-client `@Qualifier`-named beans for the five clients with their own `weavr.*.timeout` property; one default-timeout bean for everything else) — not inside each client's constructor, which would have silently broken every `MockRestServiceServer`-bound test.
- ~~Add SSRF defence and a size ceiling to `LinkExtractor` and the image download (F2, F3). Resolve first, validate the resolved address, cap redirects, abort the stream at the ceiling rather than checking afterwards.~~ Done via a new shared `SafeUrlFetcher`. **Closes the obvious case, not DNS rebinding** — pinning the validated address for the actual connection is still Part D's job, not this fix's. `PdfExtractor.download` has the identical shape and was deliberately left open — outside this phase's named scope.
- ~~Add `WEAVR_RAPID_YT_API_KEY` to `.env.example` (F10).~~ Done.

*Gate: a save against a private address is refused (verified by mocked test — not fired at a real target); a deliberately hung endpoint fails within the timeout instead of pinning the worker (verified by code review of `JdkClientHttpRequestFactory`'s timeout semantics — not fired at a real hung endpoint).*

### Phase 2 — Extractor refactor (behaviour first, boundary second)

**Landed 2026-08-15 — see [CLAUDE.md](../CLAUDE.md) for the verified writeup.**

- ~~Bounded retry with jitter inside the provider probe, distinguishing 429 and 5xx from 404 (F4); rank caption tracks by parsed prose (F6); widen the URL pattern (F8).~~ Done — see F4/F6/F8 above.
- ~~Decide F5 explicitly: either fail captionless YouTube saves with an accurate message, or route the ASR and OCR tiers through the provider too. Both are defensible; the current silent attempt at a never-successful path is not.~~ Decided: fail fast. See F5 above.
- ~~Extract a `SourceExtractor` interface inside the monolith, with the current cascade as its only implementation. This is the seam the service later slots into, and it costs nothing to introduce now.~~ Done — new `SourceExtractor.java`, `ExtractionCascade implements SourceExtractor`, `ProcessSaveHandler` now depends on the interface.

*Gate: the cascade is reachable only through the interface; no caller references `YtDlpClient` or `RapidYtClient` directly. Met — `ProcessSaveHandler` (the only external caller) already depended solely on `ExtractionCascade` before this phase; it now depends on `SourceExtractor`. `AsrTranscriber`/`VisualTextExtractor` still hold `YtDlpClient` directly, which is intentional: they are internal collaborators of the one `SourceExtractor` implementation, not external callers reaching around it.*

### Phase 3 — Extraction API (the service itself)

- `POST /extract` and `GET /health`, the contracts above, the ten error codes with their retryability fixed in one table rather than scattered across handlers.
- Port `ExternalProcess`'s discipline wholesale — dual-drained pipes, hard timeouts, output caps, temp dirs deleted in a `finally`. It is the best-tested code in the pipeline and should not be rewritten from memory.
- Port the OCR tier with its measured settings intact: the frame selector that also takes frame 0 and a periodic sample, and **no histogram equalisation**. Both were established by measurement and are easy to lose in a rewrite — see [testing.md](testing.md#what-running-the-real-binary-found-the-second-time-visual-tier-2026-08-01).

*Gate: the service answers every error code correctly against a fixture suite, and never reports partial success.*

### Phase 4 — Storage and artifacts

- Artifact write to the existing Supabase bucket under a per-save prefix; signed, expiring references in the response; no bucket keys or paths on the wire.
- A lifecycle rule that deletes artifacts after the pipeline has consumed them, plus a sweep for orphans from failed runs — the bucket is on the same free tier as everything else.

*Gate: a completed save leaves no artifact behind; a failed one leaves nothing after the sweep.*

### Phase 5 — Backend integration

- A second `SourceExtractor` implementation that calls the service, behind a config flag so both paths are live and switchable.
- Map the wire error model onto the existing `PermanentJobException` / `RetryableJobException` / `RetryAfterException` split. This is a mapping, not a new mechanism.
- Backend fetches the audio artifact and makes the Groq call; fetches frames and decides on vision escalation. No AI call moves.

*Gate: flipping the flag changes nothing observable in the app for a set of known-good URLs.*

### Phase 6 — Deployment

- Deploy to Fly.io (see the host comparison in Part E), 1–2 GB, no public ingress, shared secret in the environment, concurrency raised from 1 to match the container's memory. The memory headroom is the point of the split; leaving concurrency at 1 would waste it.
- Fix the yt-dlp freeze (F7) — bust the layer or update at container start, and record the resolved version in the health response so a stale extractor is visible rather than inferred.

*Gate: the service is unreachable from the public internet; `/health` reports the yt-dlp version actually running.*

### Phase 7 — Monitoring

- Structured logs keyed by save id and outcome code; counters per error code, per platform, and for the OCR escalation rate that CLAUDE.md already flags as the number nobody can currently see.
- An alert on the one condition that is currently silent: extraction success rate for a platform dropping to zero, which is what both a broken extractor and a new block look like.

*Gate: a deliberately broken provider key is visible from the logs within one save.*

### Phase 8 — Testing

- Unit coverage for URL normalisation, the SSRF validator (loopback, link-local, private, redirect-to-private, DNS rebinding), the error mapping, and the retry bounds.
- Contract tests the backend and service share, so the boundary cannot drift silently — this is what actually makes the extractor replaceable.
- An opt-in live test in the style of `YtDlpLiveTest` and `OcrLiveTest`, plus the thirty-Reel eval set CLAUDE.md has wanted since Phase 4, which the new service is a natural home for.

*Gate: the SSRF suite passes against every address class, and the contract tests fail loudly on a shape change.*

---

## Provenance and what is not verified

**Read from source.** Repository findings came from the repositories themselves — package manifests, extraction call sites, issue histories — not from recall. Our own findings were read from the working tree and confirmed by grep.

**Not verified.** No code was changed and nothing here was executed against the live deploy. The F1 blast radius is reasoned from the runner's thread model rather than reproduced. The F2 SSRF is confirmed by reading the fetch path rather than by firing a request at an internal address — worth doing before Phase 1 closes, since a reproduction is also the regression test.
