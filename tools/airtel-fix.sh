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
#   - Private DNS back to Automatic. ADBLOCK=1 bash airtel-fix.sh turns on AdGuard's ad-blocking DNS
#     instead (off by default: on some networks it cuts the box off from the internet)
#   - lets TV Home switch its own Home-button service back on after updates (WRITE_SECURE_SETTINGS)
#     and install its own updates from the TV (REQUEST_INSTALL_PACKAGES)
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
sh_tv pm grant $PKG android.permission.BLUETOOTH_CONNECT >/dev/null 2>&1   # TV settings > Remotes, without Android asking
echo "updates from the TV allowed"

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

say "Private DNS"
if [ "${ADBLOCK:-0}" = 1 ]; then
  sh_tv settings put global private_dns_specifier dns.adguard-dns.com
  sh_tv settings put global private_dns_mode hostname
  echo "AdGuard ad-blocking DNS on (if the internet stops working: bash airtel-fix.sh without ADBLOCK=1)"
else
  sh_tv settings put global private_dns_mode opportunistic
  echo "automatic"
fi

say "Restarting the launcher"
sh_tv am force-stop $PKG
sh_tv am start -n $PKG/.MainActivity | grep -E '^(Status|Error)' || true
echo
echo "Done. Press Home on the remote."
