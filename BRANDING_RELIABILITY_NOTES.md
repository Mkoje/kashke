# Branding and reliability update - 2026-10-06

## Changes
- Reliability with a server score and positive sample count now shows the percentage; scores below 20 retain the high-risk label. Inconclusive is reserved for absent scores/history, when the reliability bar cannot be drawn. No scoring, backend or transaction rules changed.
- Desktop Kashke_Logo_Pack copied intact into branding/Kashke_Logo_Pack, preserving SVG, GIF, PNG, JPG, web, Android and Play Store sources. Web assets are kept for web use rather than packaged as Android icons.
- Launcher density PNGs replaced, including round-icon fallback. Adaptive launchers use the new transparent symbol on the existing white background with safe insets. App header uses the new symbol. Startup uses the complete logo.
- Android loading uses animation/kashke-loop.gif through a native animated view, including session startup, sign-in, account lock loading and profile/contact lookups. The GIF's original white background is preserved in rounded tiles. Actual progress/reliability bars stay as bars.
- Animated SVG retained as an editable/browser animation source; not rendered through a WebView. No new network/library dependency.

## Validation
Android debug build passed. Physical phone shows new startup icon, header symbol and branded Discover loading. Existing-history cards showed 66% based on six agreements. No-history card retained inconclusive state. Home scrolling and log comparison found zero new crashes. Captures: output/branding-20261006.

## Rollback
Prior resources: output/branding-20261006/res-before. Prior UI: output/branding-20261006/KashKeApp.kt.before. Compare and restore these if reverting; remove the new BrandLoading.kt and newly introduced branded resources when restoring the preceding version, then rebuild. Do not overwrite later work without comparing it. Full source pack remains under branding for future design revisions.