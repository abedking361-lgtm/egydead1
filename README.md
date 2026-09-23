# EgyDead Aniyomi Extension

Aniyomi/Tachiyomi-style extension source for EgyDead.

Default site URL:

```text
https://tv10.egydead.live/h3/
```

The extension includes a setting named **Base URL** so you can change the site link later when the domain changes.

## Files

```text
src/ar/egydead/build.gradle
src/ar/egydead/src/eu/kanade/tachiyomi/extension/ar/egydead/EgyDead.kt
```

## How To Use

1. Put this module inside an `aniyomi-extensions` or compatible extensions repo.
2. Build the extension module for `ar/egydead`.
3. Install the generated APK on the same phone where Aniyomi is installed.
4. Open the source settings and update **Base URL** if the domain changes.

## Notes

Websites change their HTML often. If search, episodes, or video extraction stops working, update the CSS selectors in `EgyDead.kt`.
