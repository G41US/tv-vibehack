#!/usr/bin/env bash
# One-command setup and repair for the Airtel Smart Router (Google TV 12), run from Termux on a phone
# that is already connected with `adb connect <box-ip>:5555`.
#
#   curl -fsSLO https://raw.githubusercontent.com/G41US/tv-vibehack/main/tools/airtel-fix.sh
#   bash airtel-fix.sh                 # or: SMARTTUBE=1 bash airtel-fix.sh
#
# Safe to run any number of times. It re-applies everything an Airtel update or a launcher reinstall
# can undo, and installs nothing it hasn't been asked to:
#   - TV Home as the default home screen
#   - HomeKeyService (Home button + replaces Airtel's screen at boot), kept alongside other services
#   - the aurora theme and the Clock screensaver
#   - faster animations (0.5x)
#   - ad-blocking Private DNS (AdGuard). Undo: adb shell settings put global private_dns_mode opportunistic
#   - lets TV Home switch its own Home-button service back on after updates (WRITE_SECURE_SETTINGS)
#     and install its own updates from the TV (REQUEST_INSTALL_PACKAGES)
#   - stops background-only junk (bug tracker, unused preinstalled apps, Airtel's own home screen)
#     from running in the background. Nothing is disabled or removed; the apps still open normally.
#     Undo: UNRESTRICT=1 bash airtel-fix.sh
#   - SMARTTUBE=1 also downloads and installs SmartTube from its official GitHub release
set -u

PKG=com.gathin.tvhome
SVC=$PKG/.HomeKeyService
RAW=https://raw.githubusercontent.com/G41US/tv-vibehack/main
FILES=/sdcard/Android/data/$PKG/files
TMP="${TMPDIR:-/data/data/com.termux/files/usr/tmp}/airtel-fix"
mkdir -p "$TMP"

say() { printf '\n== %s\n' "$*"; }
sh_tv() { adb shell "$@" </dev/null 2>&1 | tr -d '\r'; }

say "Checking the connection"
if ! adb get-state </dev/null >/dev/null 2>&1; then
  echo "No box connected. Run: adb connect <box-ip>:5555   (then accept the prompt on the TV)"; exit 1
fi
echo "Connected to $(sh_tv getprop ro.product.model), Android $(sh_tv getprop ro.build.version.release)"

if [ "${SMARTTUBE:-0}" = 1 ]; then
  say "Installing SmartTube (official release)"
  if curl -fL --progress-bar -o "$TMP/smarttube.apk" \
      https://github.com/yuliskov/SmartTube/releases/download/latest/smarttube_stable.apk; then
    adb install -r "$TMP/smarttube.apk" </dev/null 2>&1 | tail -n 1
  else
    echo "Download failed; skipped."
  fi
fi

if ! sh_tv pm list packages $PKG | grep -qx "package:$PKG"; then
  echo "TV Home is not installed. Install app-release.apk first (adb install -r app-release.apk)."; exit 1
fi

say "Home screen"
sh_tv cmd package set-home-activity --user 0 $PKG/.MainActivity

say "Home button service"
CUR=$(sh_tv settings get secure enabled_accessibility_services)
case "$CUR" in
  *"$SVC"*) echo "already on" ;;
  null|"") sh_tv settings put secure enabled_accessibility_services "$SVC"; echo "turned on" ;;
  *) sh_tv settings put secure enabled_accessibility_services "$CUR:$SVC"; echo "turned on (kept: $CUR)" ;;
esac
sh_tv settings put secure accessibility_enabled 1

say "Self-repair and updates from the TV"
G=$(sh_tv pm grant $PKG android.permission.WRITE_SECURE_SETTINGS)
if [ -n "$G" ]; then G2=$(sh_tv cmd package grant $PKG android.permission.WRITE_SECURE_SETTINGS); else G2=""; fi
if sh_tv dumpsys package $PKG | grep -q "WRITE_SECURE_SETTINGS: granted=true"; then
  echo "self-repair on: after updates, TV Home turns its Home-button service back on by itself"
else
  echo "self-repair blocked by the box (${G2:-$G}); after updates, run this script again"
fi
sh_tv appops set $PKG REQUEST_INSTALL_PACKAGES allow >/dev/null
echo "updates from the TV allowed"

# Apps that only run in the background on this box. Each one is closed, kept from running in the
# background (app op RUN_ANY_IN_BACKGROUND) and put in the "restricted" standby bucket. They still
# open normally if you start them. Edit the list to taste; UNRESTRICT=1 undoes it for all of them.
RESTRICT_APPS="
com.nes.tvbugtracker
com.tulix.viewsatandroidtv
tv.africa.streaming
com.daydream.airtel
com.google.android.tvrecommendations
com.nes.hometv
"
free_ram() { sh_tv dumpsys meminfo | grep -m1 "Free RAM" | sed 's/(.*//; s/^ *//'; }

if [ "${UNRESTRICT:-0}" = 1 ]; then
  say "Letting background apps run again"
  for p in $RESTRICT_APPS; do
    sh_tv cmd appops set "$p" RUN_ANY_IN_BACKGROUND allow >/dev/null
    sh_tv am set-standby-bucket "$p" active >/dev/null
    echo "  $p: unrestricted"
  done
else
  say "Stopping junk apps running in the background"
  echo "before: $(free_ram)"
  INSTALLED=$(sh_tv pm list packages)
  for p in $RESTRICT_APPS; do
    if ! printf '%s\n' "$INSTALLED" | grep -qx "package:$p"; then
      echo "  $p: not installed, skipped"; continue
    fi
    sh_tv am force-stop "$p" >/dev/null
    A=$(sh_tv cmd appops set "$p" RUN_ANY_IN_BACKGROUND ignore)
    B=$(sh_tv am set-standby-bucket "$p" restricted)
    OP=$(sh_tv cmd appops get "$p" RUN_ANY_IN_BACKGROUND)
    BUCKET=$(sh_tv am get-standby-bucket "$p")
    case "$OP" in *ignore*) bg="background blocked" ;; *) bg="background NOT blocked${A:+ ($A)}" ;; esac
    case "$BUCKET" in 45|*estricted*) bk="restricted" ;; *) bk="bucket ${BUCKET:-?}${B:+ ($B)}" ;; esac
    echo "  $p: $bg, $bk"
  done
  sleep 3
  echo "after:  $(free_ram)"
fi

say "Theme"
if curl -fsSL -o "$TMP/config.json" "$RAW/themes/aurora/config.json" &&
   curl -fsSL -o "$TMP/wallpaper.jpg" "$RAW/themes/aurora/wallpaper.jpg"; then
  sh_tv mkdir -p $FILES
  adb push "$TMP/config.json" $FILES/config.json </dev/null >/dev/null &&
    adb push "$TMP/wallpaper.jpg" $FILES/wallpaper.jpg </dev/null >/dev/null \
    && echo "aurora applied"
else
  echo "Couldn't download the theme; skipped."
fi

say "Clock screensaver"
sh_tv settings put secure screensaver_components $PKG/.ClockDream
sh_tv settings put secure screensaver_enabled 1
echo "on"

say "Faster animations"
for k in window_animation_scale transition_animation_scale animator_duration_scale; do
  sh_tv settings put global $k 0.5
done
echo "0.5x"

say "Ad-blocking DNS"
sh_tv settings put global private_dns_mode hostname
sh_tv settings put global private_dns_specifier dns.adguard-dns.com
echo "AdGuard DNS on"

say "Restarting the launcher"
sh_tv am force-stop $PKG
sh_tv am start -n $PKG/.MainActivity | grep -E '^(Status|Error)' || true
echo
echo "Done. Press Home on the remote."
