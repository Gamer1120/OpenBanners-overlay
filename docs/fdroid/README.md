# F-Droid

`org.openbanners.overlay.yml` is the recipe for [fdroiddata](https://gitlab.com/fdroid/fdroiddata)
(`metadata/org.openbanners.overlay.yml` there).

## Reproducible builds

F-Droid builds the tagged commit itself, checks that its unsigned APK is byte-identical to the APK on the GitHub
release (`Binaries:`), and then publishes **our** signed APK (cert sha256 in `AllowedAPKSigningKeys`). So F-Droid,
GitHub and Google Play installs can update each other.

What keeps the build reproducible:
- `dependenciesInfo { includeInApk = false }` in `app/build.gradle`: the APK has no Google dependency-info signing
  block (F-Droid's scanner rejects it). The AAB keeps it for Play's SDK Index.
- The release workflow uses JDK 21, the same major version as the F-Droid buildserver (Debian trixie, OpenJDK 21).
  Do not change one without the other.
- Build-tools/AGP are pinned in Gradle; the APK embeds the git commit (`META-INF/version-control-info.textproto`), so
  only release from a clean tagged commit.

Verified for versionCode 30 (2026-10-12): `fdroid lint` clean and `fdroid build` (fdroidserver Docker image,
including its scanner) succeeds, and its unsigned APK is byte-identical to a Temurin 21 build.

## Updates

`UpdateCheckMode: Tags ^v[0-9]+$` + `AutoUpdateMode: Version`: F-Droid's bot adds a new build when a tag `vN` is
pushed, as long as `versionCode` = N and the GitHub release APK is named `OpenBanners-Overlay-vN.apk`.

## Submitting

1. Release `v30` (push the tag; the workflow publishes the signed APK).
2. Fork fdroiddata on GitLab, copy the recipe to `metadata/`, open a merge request with the "App inclusion" template.
3. Test locally: `fdroid lint org.openbanners.overlay` and `fdroid build -v -l org.openbanners.overlay`.
