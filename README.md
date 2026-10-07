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

## Banner Route Overlay (fork)

Fork of [bannergress/banner-overlay](https://github.com/bannergress/banner-overlay) (MIT) with a
Machina Path-style route strip added: a touch-through map at the top of the screen (60% wide)
showing the current mission's steps as labelled dots (`5c` = mission 5, step 3), legs in step
order, done steps in grey, your position in cyan, and the next mission's first step. It keeps the
nearest 3 open steps in view. Toggle under Settings → Route map. Installs next to the original
(application id `com.bannergress.overlay.route`). Location is requested at high accuracy.
