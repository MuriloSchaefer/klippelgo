#!/usr/bin/env bash
# Starts two Android emulators side by side, installs KlippelGo on both and opens one as the rider and the other
# as the driver. Both talk to the local testnet on your computer (http://10.0.2.2:8545 from inside an emulator).
#
# Rider and driver find each other and agree a fare over the gossip network, so this also starts its bootstrap
# node with Docker Compose (port 4001, `10.0.2.2:4001` from inside an emulator) unless one is already running.
#
# Before running: `make testnet` in one terminal and `make deploy` in another (from the repo root).
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
CORE="$HERE/../../core"
API="${API:-35}"
NDK="${NDK:-27.3.13750724}"
GOSSIP_PORT=4001
RPC_HOST_URL="${RPC_HOST_URL:-http://127.0.0.1:8545}"
RPC_IN_EMULATOR="${RPC_IN_EMULATOR:-http://10.0.2.2:8545}"

if [ -z "${ANDROID_HOME:-}" ]; then
  for d in "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    [ -n "$d" ] && [ -d "$d" ] && ANDROID_HOME="$d" && break
  done
fi
# No SDK yet: put one where Android Studio would, so installing Studio later finds the same SDK.
if [ -z "${ANDROID_HOME:-}" ]; then
  case "$(uname -s)" in Darwin) ANDROID_HOME="$HOME/Library/Android/sdk" ;; *) ANDROID_HOME="$HOME/Android/Sdk" ;; esac
fi
export ANDROID_HOME

# Gradle 8 does not run on a JDK newer than 24, so prefer a 17 or 21 when JAVA_HOME is not set.
if [ -z "${JAVA_HOME:-}" ]; then
  for d in /usr/lib/jvm/java-17-openjdk /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/java-21-openjdk \
           /usr/lib/jvm/java-21-openjdk-amd64 "/Applications/Android Studio.app/Contents/jbr/Contents/Home"; do
    [ -x "$d/bin/java" ] && export JAVA_HOME="$d" PATH="$d/bin:$PATH" && break
  done
fi
command -v java >/dev/null || { echo "Install JDK 17 and set JAVA_HOME." >&2; exit 1; }

SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
EMULATOR="$ANDROID_HOME/emulator/emulator"
ADB="$ANDROID_HOME/platform-tools/adb"

# Android Studio is not needed: the command-line tools are a plain zip, and sdkmanager fetches the rest.
if [ ! -x "$SDKMANAGER" ]; then
  case "$(uname -s)" in Darwin) TOOLS_OS=mac ;; *) TOOLS_OS=linux ;; esac
  TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-$TOOLS_OS-${CMDLINE_TOOLS_BUILD:-14742923}_latest.zip"
  echo "Downloading the Android command-line tools into $ANDROID_HOME"
  tmp="$(mktemp -d)"
  curl -fL --progress-bar -o "$tmp/tools.zip" "$TOOLS_URL"
  if command -v unzip >/dev/null; then unzip -q "$tmp/tools.zip" -d "$tmp"; else bsdtar -xf "$tmp/tools.zip" -C "$tmp"; fi
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf "$tmp"
fi

case "$(uname -m)" in arm64|aarch64) ABI=arm64-v8a ;; *) ABI=x86_64 ;; esac
IMAGE="system-images;android-$API;google_apis;$ABI"

echo "Checking the local testnet at $RPC_HOST_URL"
curl -sf -X POST -H 'Content-Type: application/json' \
  --data '{"jsonrpc":"2.0","id":1,"method":"eth_chainId","params":[]}' "$RPC_HOST_URL" >/dev/null \
  || { echo "No testnet at $RPC_HOST_URL. Run 'make testnet' and 'make deploy' first." >&2; exit 1; }

echo "Installing emulator, platform tools, Android $API, NDK $NDK and $IMAGE if needed"
yes | "$SDKMANAGER" --install "platform-tools" "emulator" "platforms;android-$API" "ndk;$NDK" "$IMAGE" >/dev/null || true
[ -x "$EMULATOR" ] && [ -x "$ADB" ] || { echo "sdkmanager could not install the emulator. Run it by hand: $SDKMANAGER --install emulator" >&2; exit 1; }

# The app's gossip network is Rust (core/), built for Android with cargo-ndk.
command -v cargo >/dev/null || { echo "Install Rust: https://rustup.rs" >&2; exit 1; }
cargo ndk --version >/dev/null 2>&1 || { echo "Installing cargo-ndk"; (cd "$CORE" && cargo install cargo-ndk); }

if ! (exec 3<>"/dev/tcp/127.0.0.1/$GOSSIP_PORT") 2>/dev/null; then
  echo "Starting the gossip bootstrap node on port $GOSSIP_PORT with Docker Compose"
  docker compose -f "$HERE/../../testnet/docker-compose.yml" up -d --build gossip
fi

start_phone() { # name port
  local name=$1 port=$2
  if ! "$EMULATOR" -list-avds | grep -qx "$name"; then
    echo "Creating emulator $name"
    echo no | "$AVDMANAGER" create avd -n "$name" -k "$IMAGE" -d pixel_7 >/dev/null
  fi
  # avdmanager leaves the computer's keyboard off, and Android then hides the on-screen one too, so nothing
  # can be typed. Android Studio's Device Manager turns it on; do the same.
  local config="${ANDROID_AVD_HOME:-$HOME/.android/avd}/$name.avd/config.ini"
  if [ -f "$config" ] && ! grep -qx "hw.keyboard=yes" "$config"; then
    grep -v "^hw.keyboard=" "$config" >"$config.tmp" || true
    echo "hw.keyboard=yes" >>"$config.tmp"
    mv "$config.tmp" "$config"
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
echo "On the rider: tap 'Pedir corrida'. On the driver: set your price and tap 'Enviar oferta'."
