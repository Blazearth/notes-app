# Legal pages

Hosted documents that have to exist at a **public URL** — not in-app screens.

| File | Purpose |
|---|---|
| `privacy.html` | Privacy Policy, v1.0. Play Console → Store listing → Privacy policy URL, and Settings → Privacy policy in the app. |

`privacy.html` is a single self-contained file: no build step, no external
fonts, scripts, stylesheets or images. That is deliberate — a privacy policy
that loads a font from a third-party CDN sends every reader's IP address to
that CDN, which is exactly the thing the page is meant to be honest about.

Its colours, radii, spacing and type scale are lifted from
`app/src/theme/palettes.ts` and `app/src/theme/typography.ts` (surface family
`weavr`, accent `weavr`), so it reads as the same product as the app. Sora is
not loaded; the page uses the platform sans stack the app falls back to.

## Before publishing

1. **Fill in every `[BRACKETED PLACEHOLDER]`.** They render as dashed inline
   chips so an unfilled one is impossible to miss. `grep -o '\[[A-Z][^]]*\]' privacy.html`
   lists them all.
2. **Resolve every "Legal review required" callout.** These are decisions, not
   wording — minimum age vs. the DPDP Act's under-18 rule, the public storage
   bucket, transfer mechanisms, whether an EU/UK representative is needed.
3. **Re-check the AI tier disclosure** (§4) if the Gemini tier ever changes.
   Free-tier terms permit model improvement; paid terms differ, and the
   Play Data safety answers change with them.

## Hosting

Any static host works. Two options:

**GitHub Pages** — `.github/workflows/legal-pages.yml` publishes *only* this
directory, so the rest of `docs/` (competitive analysis, testing notes,
release checklists) is never exposed. Enable it at Settings → Pages → Source:
GitHub Actions. Note that **Pages on a private repository requires a paid
GitHub plan**; on the free plan either make a separate public repo for this
directory or use another host.

**Anything else** — Cloudflare Pages, Netlify, Vercel, or an S3 bucket. The
file has no dependencies, so `privacy.html` uploaded anywhere is the whole
deployment.

## After hosting

1. Set `PRIVACY_POLICY_URL` in [`app/src/legal/links.ts`](../app/src/legal/links.ts).
   Until it is set, the Settings row hides itself rather than opening nothing.
2. Play Console → Store listing → **Privacy policy URL** → the page URL.
3. Play Console → App content → **Data deletion** → the page URL plus
   `#account-deletion`. That section is written to serve as the deletion-request
   page (it covers both the in-app route and the email route), which is why
   there is no second document to keep in sync.
