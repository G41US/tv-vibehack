#!/usr/bin/env bash
# Build the launcher APK and install it on the TV as the default home screen.
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || echo /opt/homebrew/opt/openjdk@17)}"
export ANDROID_HOME="${ANDROID_HOME:-$([ -d ~/Library/Android/sdk ] && echo ~/Library/Android/sdk || echo /opt/homebrew/share/android-commandlinetools)}"
export PATH="$JAVA_HOME/bin:$PATH"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleRelease -q --console=plain
PY=../tools/venv/bin/python
[ -x "$PY" ] || PY=../tools/venv/Scripts/python.exe
export MSYS_NO_PATHCONV=1
# Which app owns Home differs by build (com.google.android.tvlauncher on Android TV,
# com.google.android.apps.tv.launcherx on newer Google TV), so ask the TV instead of guessing.
# Ignore an answer of our own launcher, left over from a previous run of this script.
if [ -z "${STOCK_LAUNCHER:-}" ]; then
  STOCK_LAUNCHER=$("$PY" -I ../tools/tv.py \
    'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' \
    | tail -n 1 | cut -d/ -f1 || true)
  case "$STOCK_LAUNCHER" in
    com.gathin.tvhome | *[!a-zA-Z0-9._]* | "") STOCK_LAUNCHER=com.google.android.tvlauncher ;;
  esac
  echo "Stock launcher: $STOCK_LAUNCHER"
fi
APK=app/build/outputs/apk/release/app-release.apk
"$PY" -I ../tools/b64push.py "$APK" /data/local/tmp/tvhome.apk
printf '%s\n' \
  "pm install -r /data/local/tmp/tvhome.apk" \
  "rm -f /data/local/tmp/tvhome.apk" \
  "pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION 2>/dev/null; echo location-granted" \
  "cmd package compile -m speed -f com.gathin.tvhome" \
  "cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity" \
  "pm disable-user --user 0 $STOCK_LAUNCHER" \
  | "$PY" -I ../tools/tv.py --batch

# Optional accessibility services. Off unless asked for, and Android switches them off again on
# every reinstall, so pass the flag each time you run this script.
#   INPUT_KEY_SERVICE=1   the remote's Input button (KEYCODE_TV) opens the input switcher from any
#                         app; Google TV needs this once the stock launcher is gone (InputKeyService)
#   HOME_KEY_SERVICE=1    remotes whose Home button sends KEYCODE_ALL_APPS instead of HOME, e.g. Xiaomi
#                         sticks: the key is routed to the launcher (HomeKeyService)
enable_service() {   # appends to enabled_accessibility_services without dropping other services
  svc=com.gathin.tvhome/.$1
  "$PY" -I ../tools/tv.py "s=\$(settings get secure enabled_accessibility_services); case \"\$s\" in *$1*) ;; null|'') settings put secure enabled_accessibility_services $svc ;; *) settings put secure enabled_accessibility_services \"\$s:$svc\" ;; esac; settings put secure accessibility_enabled 1; echo $1 enabled"
}
if [ "${INPUT_KEY_SERVICE:-0}" = 1 ]; then enable_service InputKeyService; fi
if [ "${HOME_KEY_SERVICE:-0}" = 1 ]; then enable_service HomeKeyService; fi
echo "Installed. Press Home on the remote to see it."
