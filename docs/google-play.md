# Publishing OpenBanners Overlay on Google Play

Checklist and ready-to-paste answers for the Play Console. Application id: `org.openbanners.overlay`.

## Build

- Push a tag `vN` (after raising `versionCode` in `app/build.gradle`). The GitHub release then contains
  `OpenBanners-Overlay-vN.aab` (upload this to Play) and `OpenBanners-Overlay-vN.apk` (sideloading).
- Target SDK 36 (Android 16), as required for new apps and updates since 31 August 2026.

## Release flow: tag → open test, night → production

1. **Tag push → open testing (beta).** Push a tag `vN`. The Release workflow builds the signed APK + AAB, publishes the
   GitHub release, then its "Google Play" job uploads that same AAB to the **beta** track (open testing), status
   `completed`, notes from `changelogs/N.txt`. Testers get every release first. The very first AAB had to be uploaded
   by hand in Play Console; the API only adds later versions.
2. **Night → production.** The "Promote to production" workflow (`.github/workflows/promote.yml`,
   `scripts/play_promote.py`) moves the newest tested version to **production** with the bundle that is already on
   Play: no rebuild, the same file testers have.

The closed test (alpha) is retired (its Google Group was removed) and internal (v18) is left alone. The Release
workflow can't upload to production any more; production is only reached by the promotion.

### Nightly production promotion

- Runs hourly at :15 from 22:15 to 04:15 UTC. A scheduled run only acts when it is **00:00–05:59 in
  Europe/Amsterdam**; the other runs exit as no-ops. That covers summer and winter time (CEST: 22:15–03:15 UTC act,
  CET: 23:15–04:15 UTC act), and the extra runs are retries.
- **Cutoff** = 00:00 Europe/Amsterdam of the current Amsterdam date. A tag `vN` is a candidate when:
  - N > the versionCode on production;
  - the tagged commit's committer date is before the cutoff (a release tagged today waits until tonight; one
    tagged at 00:30 waits until the next night);
  - N <= the newest versionCode *published* on beta (a beta release still in Play review doesn't count yet);
  - the bundle for N is in Play's bundle library (`edits.bundles.list`; older beta bundles stay there after a
    newer beta release, so they can still be promoted);
  - there is no `hold-vN` tag.
- The newest candidate is put on production in one edit: status `completed` (full rollout), release name N, and
  release notes = the changelogs of every version since production (production < n <= N), newest first, separated
  by a blank line. Play allows 500 characters per language: the oldest changelogs are dropped first, or the oldest
  one that still partly fits is cut with "…". Changelogs of held versions are left out (write anything a held
  version brought into the next version's changelog). Every language folder under `fastlane/metadata/android/` is
  handled the same way; today only `en-US` exists.
- **Idempotent:** if production already has N or newer, the run is a no-op. Because all runs of one night share the
  same cutoff, production gets at most one scheduled update per Amsterdam day.
- Every run writes what it saw and did to the job summary (Actions → the run → Summary).
- The workflow and the Release workflow's Google Play job share the concurrency group `play-edits` (no cancel),
  so they never commit Play edits at the same moment. GitHub keeps only one *pending* run per group: in the rare
  case that a third run arrives while one runs and one waits, the waiting one is cancelled. If a Release run's
  "Google Play" job shows *cancelled*, re-run it.

### Switches

| What | How |
|---|---|
| **Hold** a version (never promote N) | `git tag hold-vN <commit-or-vN> && git push origin hold-vN` (e.g. `git tag hold-v58 v58`). Remove: `git tag -d hold-vN && git push origin :refs/tags/hold-vN`. Older, unheld versions can still be promoted. F-Droid (`^v[0-9]+$`) and the Release workflow (`v*`) ignore these tags. |
| **Pause** scheduled promotion | `gh variable set PLAY_PROMOTION_PAUSED --body true -R Gamer1120/OpenBanners-overlay` (or Settings → Secrets and variables → Actions → Variables). Resume with `--body false`. Scheduled runs then log "paused" and do nothing; dry-run and promote-now still work. |
| **Dry run** | Actions → Promote to production → Run workflow → mode `dry-run` (or `gh workflow run promote.yml -R Gamer1120/OpenBanners-overlay -f mode=dry-run`). Locally: `python3 scripts/play_promote.py --dry-run` (uses `~/.config/openbanners-signing/play-service-account.json`). Prints production, beta, candidates, cutoff and notes, lets Play validate the track change, then deletes the edit without committing. During the day it evaluates as for the coming night. |
| **Promote now** | Actions → Promote to production → Run workflow → mode `promote-now` (or `gh workflow run promote.yml -R Gamer1120/OpenBanners-overlay -f mode=promote-now`). Ignores cutoff, window and pause, respects holds, promotes the newest version published on beta. With `-f version=59` it promotes exactly that version (bundle must be in the library and newer than production), even when held. This is the only way to get a second production update on one day. |

Current holds: `hold-v58` (v58 has no app changes since v57 and beta-only release notes; production waits for the
next real release).

Tests for the decision logic (cutoff/DST, hold, idempotency, notes): `python3 -m unittest discover -s scripts`; the
workflow runs them before every promotion.

### Changes in review

Google Play reviews changes app-wide: while *anything* is in review (a beta release, a production release, a store
listing change), a new commit either waits or cancels that review. `edits.commit` defaults to
`CANCEL_IN_REVIEW_AND_SUBMIT`, which silently cancels the review and resubmits everything (the old
r0adkll/upload-google-play action used that default). Both scripts therefore commit with
`changesInReviewBehavior=ERROR_IF_IN_REVIEW`:

- **Promotion:** it first checks `tracks.releases.list` for production/beta releases in review and, if there is
  one, stops without committing (exit code 2, job fails with a clear message). If something else is in review, the
  commit itself is refused the same way. Nothing is cancelled; the next hourly run in the window retries, otherwise
  the next night. A beta release that is still in review also isn't "published on beta", so it is never promoted.
  `tracks.releases.list` has a small, undocumented quota ("Listing releases quota exceeded"); when it is hit the
  script continues with the edit's tracks and relies on the commit check.
- **Tag upload (Release workflow):** if a production promotion (or anything else) is in review, the Google Play
  job fails with "other changes are in review … Nothing was cancelled". The GitHub release is already published.
  Retry once the review is done: Actions → the failed run → "Re-run failed jobs" (or
  `gh run rerun <run-id> --failed -R Gamer1120/OpenBanners-overlay`); the upload reuses the bundle if Play already
  has it. To cancel the other review on purpose: Actions → Release → Run workflow, "Use workflow from" = the tag,
  `cancel_review` ticked.
- Two tags in quick succession: the second upload fails while the first beta release is in review; re-run it later
  (or use `cancel_review` to replace the beta release under review).

### Manual uploads

Actions → Release → Run workflow, "Use workflow from" = the tag: rebuilds and uploads to `beta`, `alpha` or
`internal`, optionally `play_status=draft` (approve in Play Console yourself). Production: only via the promotion
(nightly or promote-now), never a rebuild.

## App signing

Use **Play App Signing with the existing OpenBanners key** ("Use existing app signing key from Java
keystore" → PEPK tool), not a Google-generated key. Then Play installs and GitHub/sideloaded APKs are
signed with the same key and can update each other. The key is kept outside the repo
(`~/.config/openbanners-signing/`). Certificate SHA-256:
`35:ED:71:71:43:9E:A0:0B:47:E5:56:00:5D:C6:DA:FC:EF:71:FD:F5:38:43:BC:F1:EA:5A:33:19:CF:2F:7D:58`.

## Testing requirement (new personal developer accounts)

Closed test with at least 12 opted-in testers for 14 consecutive days before applying for production. Done:
production access was granted on 2026-11-10 (versionCode 57 was the first production release).

## Store listing

Graphics and text are also in `fastlane/metadata/android/en-US/` (icon 512×512, feature graphic
1024×500, 4 phone screenshots 1080×1920, 4 seven-inch tablet screenshots 1200×1920 and 4 ten-inch tablet screenshots 1600×2560, title/short/full description). The location declaration demo
video is not in the repo; upload it unlisted to YouTube and paste that link.

- **App name:** OpenBanners Overlay
- **Short description (max 80):** Route map overlay and mission tracker for Ingress mission banners.
- **Category:** Tools (alternative: Maps & Navigation)
- **Privacy policy:** https://openbanners.org/overlay/privacy
- **Full description:**

  OpenBanners Overlay is a companion app for doing mission banners in Ingress Prime.

  Share a banner link from OpenBanners or Bannergress with the app and it shows a small overlay on top of
  Ingress:
  • A route map at the top of the screen with the steps of your current mission (labelled 1a, 1b, …),
    the start of the next mission, and your own position. It zooms so your nearest open steps are
    always in view.
  • Mission controls: see which mission you are on and open the next mission in Ingress with one tap.
  • Steps are ticked off automatically when you get within range, with an optional notification.
  • Passphrases: copy a mission's passphrase with the 🔑 button on the mission card. Choose the mission number, or crowd-sourced passphrases shared by other players on OpenBanners (optional, off by default).
  • Optionally show the whole banner: completed missions, the current one and the ones still ahead, each in its own colour.
  • Choose your own colours for the current mission, upcoming missions, completed steps and your position.
  • The map turns with your compass, or keep it north up if you prefer.
  • Arrange the mission card your way: choose and reorder its buttons, and make it wide, tall, smaller or larger.
  • Share your layout with friends, or import theirs.
  • Optionally back up your settings to OpenBanners and restore them on another phone (sign in with Bannergress).

  The overlay lets touches pass through to Ingress, so you can keep playing as usual.

  Free and open source (MIT): https://github.com/Gamer1120/OpenBanners-overlay
  Based on Banner Overlay by The Bannergress Team. Not affiliated with or endorsed by Bannergress or
  Niantic. Ingress is a trademark of Niantic.

Keep Ingress/Niantic artwork and logos out of the icon, feature graphic and screenshots' framing; screenshots
of the overlay in use are fine.

## App content forms

- **Ads:** No ads.
- **App access:** All functionality available without special access. Mention that a banner
  link has to be shared with the app, e.g. https://openbanners.org/banner/level-8-week-2026-4458, and that the
  optional Settings → OpenBanners backup signs in with any Google account through Bannergress on openbanners.org
  (no app-specific account or test credentials needed). If Play review asks for credentials anyway, switch to
  "All or some functionality is restricted" and give these same instructions.
- **Target audience:** 18+ (or 13+); not designed for children.
- **Content rating questionnaire:** category "Utility, productivity, communication, or other"; answer
  No to violence, sexuality, language, controlled substances, gambling; users cannot interact or share
  content; shares location with other users: No.
- **Government / financial / health / news app:** No.

### Data safety

Final answers from versionCode 51 (optional settings backup). Play counts data as "collected" when the app sends
it off the device to the developer's server, even if optional; location stays on the device and is not collected.
The Data safety form can't be set through the Play Developer API (the service account only uploads releases), so
enter it by hand in Play Console → Policy → App content → Data safety:

1. **Data collection and security**
   - Does your app collect or share any of the required user data types? **Yes**
   - Is all of the user data collected by your app encrypted in transit? **Yes** (HTTPS only)
   - Which methods of account creation does your app support? **OAuth** (the backup is kept under the user's
     Bannergress/Google sign-in on openbanners.org). Delete-account link: https://openbanners.org/privacy
     (explains ⋮ → Settings → delete; signing in there is enough, no app needed).
   - Do you provide a way for users to request that their data is deleted? **Yes**, URL:
     https://openbanners.org/privacy (deletion in ⋮ → Settings on openbanners.org).
2. **Data types:** tick only **App activity → Other user-generated content**. Leave everything else unticked
   (Location: not collected, it never leaves the device; Personal info / User IDs: the app sends only a
   short-lived backup token, never the account ID or e-mail; Device or other IDs: none; App info and
   performance: no crash logs or analytics).
3. **Other user-generated content → questions**
   - Collected: **Yes**. Shared: **No** (only stored on openbanners.org, the developer's own service).
   - Is this data processed ephemerally? **No** (the backup is stored until replaced or deleted).
   - Is this data required, or can users choose whether it's collected? **Users can choose** (optional).
   - Why is this user data collected? **App functionality** only.
   - Covers: the settings backup (overlay, mission card, route map and compass options) and the "mission done"
     marks the app writes to a Mission Day plan the user opened from an openbanners.org/planner link.
   - versionCode 52 (passphrases from OpenBanners, optional): no change. The download sends only the public banner
     ID and receives public data; nothing about the user leaves the device, so it isn't "collected".
4. **Preview** shows "Data collected: App activity", "Data is encrypted in transit", "You can request that data
   be deleted", "No data shared with third parties". Submit; the change goes live without a new release.

### Permissions declarations

- **Foreground service type `location`** (`FOREGROUND_SERVICE_LOCATION`):
  - Feature: "Banner progress tracking while the user plays Ingress."
  - Description: "While the user is doing an Ingress mission banner, a user-started foreground service
    shows an overlay on top of Ingress with a route map and mission controls. It needs continuous
    precise location to draw the user's position and to tick off mission steps as the user walks past
    them. The service runs only while the overlay is visible and stops when the user taps Stop in the
    notification."
  - Impact if deferred/interrupted: route map and step tracking stop while the user is walking.
  - Video: screen recording of starting the overlay from a shared banner link, walking past steps (they
    turn grey), and stopping it from the notification.
- **Location:** foreground only (`ACCESS_FINE_LOCATION`, no background location permission), so no
  background location declaration is needed.
- **Display over other apps** (`SYSTEM_ALERT_WINDOW`): no Play declaration form; explain in the
  description that the overlay is shown on top of Ingress.
