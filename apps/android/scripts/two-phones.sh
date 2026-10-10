#!/usr/bin/env bash
# Starts two Android emulators side by side, installs KlippelGo on both and opens one as the rider and the other
# as the driver. Both talk to the local testnet on your computer (http://10.0.2.2:8545 from inside an emulator).
#
# Before running: `make testnet` in one terminal and `make deploy` in another (from the repo root).
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
API="${API:-35}"
RPC_HOST_URL="${RPC_HOST_URL:-http://127.0.0.1:8545}"
RPC_IN_EMULATOR="${RPC_IN_EMULATOR:-http://10.0.2.2:8545}"

if [ -z "${ANDROID_HOME:-}" ]; then
  for d in "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    [ -n "$d" ] && [ -d "$d" ] && ANDROID_HOME="$d" && break
  done
fi
[ -n "${ANDROID_HOME:-}" ] || { echo "Set ANDROID_HOME to your Android SDK (Android Studio > Settings > Android SDK)." >&2; exit 1; }

SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
EMULATOR="$ANDROID_HOME/emulator/emulator"
ADB="$ANDROID_HOME/platform-tools/adb"
[ -x "$SDKMANAGER" ] || { echo "Install 'Android SDK Command-line Tools (latest)' from Android Studio's SDK Manager." >&2; exit 1; }

case "$(uname -m)" in arm64|aarch64) ABI=arm64-v8a ;; *) ABI=x86_64 ;; esac
IMAGE="system-images;android-$API;google_apis;$ABI"

echo "Checking the local testnet at $RPC_HOST_URL"
curl -sf -X POST -H 'Content-Type: application/json' \
  --data '{"jsonrpc":"2.0","id":1,"method":"eth_chainId","params":[]}' "$RPC_HOST_URL" >/dev/null \
  || { echo "No testnet at $RPC_HOST_URL. Run 'make testnet' and 'make deploy' first." >&2; exit 1; }

echo "Installing emulator, platform tools and $IMAGE if needed"
yes | "$SDKMANAGER" --install "platform-tools" "emulator" "$IMAGE" >/dev/null

start_phone() { # name port
  local name=$1 port=$2
  if ! "$EMULATOR" -list-avds | grep -qx "$name"; then
    echo "Creating emulator $name"
    echo no | "$AVDMANAGER" create avd -n "$name" -k "$IMAGE" -d pixel_7 >/dev/null
  fi
  if ! "$ADB" devices | grep -q "emulator-$port"; then
    echo "Starting $name on emulator-$port"
    nohup "$EMULATOR" -avd "$name" -port "$port" -no-snapshot-save -no-boot-anim >"/tmp/$name.log" 2>&1 &
  fi
}

wait_boot() { # serial
  "$ADB" -s "$1" wait-for-device
  until [ "$("$ADB" -s "$1" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
  echo "$1 is up"
}

start_phone KlippelGo_Rider 5554
start_phone KlippelGo_Driver 5556
wait_boot emulator-5554
wait_boot emulator-5556

echo "Building the app"
(cd "$HERE" && ./gradlew -q :app:assembleDebug)
APK="$HERE/app/build/outputs/apk/debug/app-debug.apk"

for serial in emulator-5554 emulator-5556; do
  "$ADB" -s "$serial" install -r "$APK" >/dev/null
done

"$ADB" -s emulator-5554 shell am start -n org.klippelgo.app/.MainActivity --es role rider --es rpc "$RPC_IN_EMULATOR" >/dev/null
"$ADB" -s emulator-5556 shell am start -n org.klippelgo.app/.MainActivity --es role driver --es rpc "$RPC_IN_EMULATOR" >/dev/null

echo
echo "Rider is on emulator-5554 (KlippelGo_Rider), driver on emulator-5556 (KlippelGo_Driver)."
echo "On the rider: pick the driver, keep R\$ 23,40 and tap 'Pedir corrida'."
