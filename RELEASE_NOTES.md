## What's new
- Faster startup and smoother board scrolling by loading saved accounts, offline caches, and project settings in the background with fewer network requests.
- Automatic re-sign-in alerts and an in-app banner when a Personal Access Token expires or is revoked.
- Hardened release builds with R8 optimization, strict HTTPS-only network policy, and lock-screen notification privacy.

## Fixes
- Prevented offline cached work items from being shown before an entered Personal Access Token is verified.
- Stopped pre-filling stored Personal Access Tokens in Sign-In and Settings so saved credentials stay hidden and can only be replaced.
- Scoped screen-capture protection (`FLAG_SECURE`) on Sign-In and Settings to release builds so debug previews render normally while release builds stay protected.
- Stopped background notification polling when the app is not visible and added automatic retry backoff for rate-limited (`429`/`503`) API responses.
- Escaped plain-text work item descriptions before saving to HTML fields and ensured tokens are never included in diagnostic text.
