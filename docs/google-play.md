# Publishing OpenBanners Overlay on Google Play

Checklist and ready-to-paste answers for the Play Console. Application id: `org.openbanners.overlay`.

## Build

- Push a tag `vN` (after raising `versionCode` in `app/build.gradle`). The GitHub release then contains
  `OpenBanners-Overlay-vN.aab` (upload this to Play) and `OpenBanners-Overlay-vN.apk` (sideloading).
- Target SDK 36 (Android 16), as required for new apps and updates since 31 August 2026.

## Automatic uploads

The release workflow uploads the AAB of every `vN` tag to Google Play once the repository secret
`PLAY_SERVICE_ACCOUNT_JSON` (a service account JSON key with release access to this app in Play Console) exists.
Track: repository variable `PLAY_TRACK` (default `alpha`, the closed testing track). Status: `PLAY_RELEASE_STATUS`
(default `completed`; `draft` to approve each release in Play Console). Release notes: `changelogs/<versionCode>.txt`.
The very first AAB has to be uploaded by hand in Play Console; the API can only add later versions.

## App signing

Use **Play App Signing with the existing OpenBanners key** ("Use existing app signing key from Java
keystore" → PEPK tool), not a Google-generated key. Then Play installs and GitHub/sideloaded APKs are
signed with the same key and can update each other. The key is kept outside the repo
(`~/.config/openbanners-signing/`). Certificate SHA-256:
`35:ED:71:71:43:9E:A0:0B:47:E5:56:00:5D:C6:DA:FC:EF:71:FD:F5:38:43:BC:F1:EA:5A:33:19:CF:2F:7D:58`.

## Testing requirement (new personal developer accounts)

Closed test with at least 12 opted-in testers for 14 consecutive days before applying for production.

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
  • Optionally copy the mission number to the clipboard for passphrase missions.
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
