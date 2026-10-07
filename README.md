# Banner Overlay

An overlay to easily start missions within a Banner.

## Advantages
* saves time searching for next mission
* prevent you from starting a wrong mission (Burlies)
* shows a number of current mission
* do not require apps switching when you use website or Telegram bot for this purpose

## How to use

Just share the banner or mission link to the app using the "Share" function from wherever you want. 
On the first start, the app will ask you for overlay permission, this is required to run the app. 
You can drag and drop the app to any place on your screen.

## OpenBanners (fork)

OpenBanners is a fork of [bannergress/banner-overlay](https://github.com/bannergress/banner-overlay)
(MIT, Copyright (c) 2022 The Bannergress Team; see LICENSE) with a route map added: a touch-through
map at the top of the screen, from the control card's right edge to the right edge of the screen,
showing the current mission's steps as labelled dots (`5c` = mission 5, step 3), legs in step order,
done steps in grey, your position in cyan, and the next mission's first step. It keeps the nearest
3 open steps in view. Toggle under Settings → Route map. Location is requested at high accuracy.

Not affiliated with Bannergress or Niantic. Application id `org.openbanners.overlay`; own icon.
Licence notices shipped in the app: `app/src/main/res/raw/licenses.txt` (Settings → Licences).
Release builds are signed with the key described in `~/.config/openbanners-signing/keystore.properties`
(kept outside the repo); without it, `assembleRelease` produces an unsigned APK.
