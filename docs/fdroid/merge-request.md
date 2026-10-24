Title: New app: OpenBanners Overlay

Source branch: Gamer1120/fdroiddata:org.openbanners.overlay → fdroid/fdroiddata:master

---

OpenBanners Overlay is a floating overlay that is shown on top of Ingress. It shows the steps of a Bannergress banner (a series of missions) one by one, together with a small route map. It's a fork of [Bannergress Overlay](https://github.com/bannergress/banner-overlay) (MIT), with a route map, compass and card layout options added.

* Source: https://github.com/Gamer1120/OpenBanners-overlay
* License: MIT
* Fastlane metadata (description, screenshots, changelogs) is in the repo under `fastlane/metadata/android/en-US`.

**Reproducible build:** The recipe uses `Binaries:` + `AllowedAPKSigningKeys:`. The upstream release APK is built by GitHub Actions with OpenJDK 21. I checked locally with `fdroid build` in `registry.gitlab.com/fdroid/docker-executable-fdroidserver:master`: the scanner passes, and the unsigned APK is byte-identical to the release build. The dependency-info block is disabled for APKs.

**Anti-features:**
* `NonFreeNet`: the app loads banners from the Bannergress API.
* `NonFreeDep`: the app is meant to be used together with Ingress (proprietary) and opens missions in it.

### Required
* [x] The app complies with the [inclusion criteria](https://f-droid.org/docs/Inclusion_Policy)
* [x] The original app author has been notified (and does not oppose the inclusion): I'm the author of this fork. Upstream is MIT, and its copyright notice is kept.
* [x] All related [fdroiddata](https://gitlab.com/fdroid/fdroiddata/issues) and [RFP issues](https://gitlab.com/fdroid/rfp/issues) have been referenced in this merge request: none
* [x] Builds with `fdroid build` and all pipelines pass

### Strongly recommended
* [x] The upstream app source code repo contains the app metadata _(summary/description/images/changelog/etc)_ in a [Fastlane](https://gitlab.com/snippets/1895688) or [Triple-T](https://gitlab.com/snippets/1901490) folder structure
* [x] Releases are tagged

### Suggested
* [ ] External repos are added as git submodules instead of srclibs: n/a
* [x] Enable [Reproducible Builds](https://f-droid.org/docs/Reproducible_Builds)
* [ ] Multiple apks for native code: n/a (no native code)
