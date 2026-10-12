#!/usr/bin/env bash
# Brings up everything the end-to-end tests run against, and nothing is a stand-in:
#
#   chain      the node at KLIPPELGO_RPC if one answers, else a local anvil (testnet/anvil.sh)
#   contracts  deployed and seeded if the chain does not have them yet (testnet/deploy.sh)
#   map pack   the pilot city's pack, built if missing (about 15 minutes, once) and activated through governance
#   gossip     the bootstrap node the phones dial, on port 4001
#   mirror     the pack mirror the app downloads from, on port 4002
#   router     Valhalla on the pack's own tiles, on port 8002: where the peer riders get their routes
#   phone      a headless emulator of its own (KlippelGo_Test), with the app and its UI agent installed
#              PHONES=3 starts three, for the tests where three apps ride together
#
#   tests/stack.sh up        bring up whatever is missing; what is already running is used as it is
#   tests/stack.sh down      stop what `up` started, and only that
#   tests/stack.sh status    say what is running
#
#   KLIPPELGO_RPC=http://host:8545 tests/stack.sh up     use a chain that already exists. It must be a local
#       testnet (anvil, chain id 31337): the tests sign with its public keys and move its clock. Gossip and
#       the pack mirror are expected on the same host as the chain, because that is where the app looks.
#   HEADLESS=0 tests/stack.sh up                         show the emulators' windows. An emulator of this
#       script's that is running the other way is restarted
#   PHONES=3 tests/stack.sh up                           three emulators: emulator-5570, -5572 and -5574
#   KLIPPELGO_PHONE=<adb serial> tests/stack.sh up       use an emulator that is already running
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
RUN="$HERE/.run"
mkdir -p "$RUN"

RPC="${KLIPPELGO_RPC:-http://127.0.0.1:8545}"
DEPLOYMENT="${KLIPPELGO_DEPLOYMENT:-$ROOT/deployments/local.json}"
REGION="${REGION:-br-pr-cascavel}"
BUILD="$ROOT/mappack/build/$REGION"
PACK="${KLIPPELGO_PACK:-$BUILD/pack}"
VALHALLA_URL="${KLIPPELGO_VALHALLA:-http://127.0.0.1:8002}"
# The same Valhalla the pack's tiles were built with (mappack/build.sh).
VALHALLA_IMAGE="ghcr.io/valhalla/valhalla@sha256:ff426b01a20f342b5d1e7ed139ea8132eba4bc1299622b70a8cd6073d85125e5"
VALHALLA_CONTAINER=klippelgo-tests-valhalla
AVD=KlippelGo_Test
EMULATOR_PORT=5570
PHONES="${PHONES:-1}"
case "$PHONES" in 1|2|3) ;; *) echo "PHONES must be 1, 2 or 3" >&2; exit 1 ;; esac
SERIAL="${KLIPPELGO_PHONE:-emulator-$EMULATOR_PORT}"
API="${API:-35}"
APP=org.klippelgo.app

HOST="$(printf '%s' "$RPC" | sed -E 's#^[a-z]+://([^:/]+).*#\1#')"
case "$HOST" in 127.0.0.1|localhost) LOCAL=1 ;; *) LOCAL=0 ;; esac

if [ -z "${ANDROID_HOME:-}" ]; then
  for d in "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    [ -n "$d" ] && [ -d "$d" ] && ANDROID_HOME="$d" && break
  done
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
# Gradle 8 does not run on a JDK newer than 24, so prefer a 17 or 21 when JAVA_HOME is not set.
if [ -z "${JAVA_HOME:-}" ]; then
  for d in /usr/lib/jvm/java-17-openjdk /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/java-21-openjdk \
           /usr/lib/jvm/java-21-openjdk-amd64 "/Applications/Android Studio.app/Contents/jbr/Contents/Home"; do
    [ -x "$d/bin/java" ] && export JAVA_HOME="$d" PATH="$d/bin:$PATH" && break
  done
fi
ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR="$ANDROID_HOME/emulator/emulator"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"

say() { printf '%s\n' "$*"; }
die() { printf '%s\n' "$*" >&2; exit 1; }
listening() { (exec 3<>"/dev/tcp/$1/$2") 2>/dev/null; }
chain_up() { cast chain-id --rpc-url "$RPC" >/dev/null 2>&1; }
valhalla_up() { curl -sf -m 2 "$VALHALLA_URL/status" >/dev/null 2>&1; }
phone_up() { [ "$("$ADB" -s "${1:-$SERIAL}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; }
# This computer is 10.0.2.2 from inside an emulator.
if [ "$LOCAL" = 1 ]; then PHONE_HOST=10.0.2.2; else PHONE_HOST="$HOST"; fi
phone_reaches_chain() { "$ADB" -s "$1" shell "ping -c1 -W2 $PHONE_HOST >/dev/null 2>&1 && echo yes" 2>/dev/null | grep -q yes; }
# The nth phone: the first may be one the user named, the others are always this script's own emulators.
port_of() { echo $((EMULATOR_PORT + 2 * ($1 - 1))); }
serial_of() { if [ "$1" = 1 ]; then echo "$SERIAL"; else echo "emulator-$(port_of "$1")"; fi; }
avd_of() { if [ "$1" = 1 ]; then echo "$AVD"; else echo "$AVD$1"; fi; }

start_phone() { # index, headless|window
  local i=$1 mode=$2 serial port avd
  serial="$(serial_of "$i")"; port="$(port_of "$i")"; avd="$(avd_of "$i")"
  if phone_up "$serial"; then
    # One of this script's emulators running without a window when one was asked for, or the reverse: start it again.
    if [ "$serial" = "emulator-$port" ] && [ -f "$RUN/emulator-$port.mode" ] && [ "$(cat "$RUN/emulator-$port.mode")" != "$mode" ]; then
      say "phone $i    restarting $serial to run ${mode}"
      "$ADB" -s "$serial" emu kill >/dev/null 2>&1 || true
      for _ in $(seq 1 30); do "$ADB" devices | grep -q "^$serial" || break; sleep 1; done
    else
      return 0
    fi
  fi
  [ "$serial" = "emulator-$port" ] || die "No phone answers as $serial."
  case "$(uname -m)" in arm64|aarch64) ABI=arm64-v8a ;; *) ABI=x86_64 ;; esac
  if ! "$EMULATOR" -list-avds | grep -qx "$avd"; then
    echo no | "$AVDMANAGER" create avd -n "$avd" -k "system-images;android-$API;google_apis;$ABI" -d pixel_7 >/dev/null \
      || die "Could not create the emulator. Run 'make android-two-phones' once: it installs the system image."
  fi
  local window=()
  [ "$mode" = headless ] && window=(-no-window -gpu swiftshader_indirect)
  nohup "$EMULATOR" -avd "$avd" -port "$port" -no-audio -no-boot-anim -no-snapshot-save "${window[@]}" >"$RUN/emulator-$port.log" 2>&1 &
  echo "$mode" >"$RUN/emulator-$port.mode"
  started "emulator-$port"
}
# Remembers that this script started something, so `down` stops it and leaves alone what was already there.
started() { echo "$1" >>"$RUN/started"; }
wait_for() { # what, seconds, command...
  local what=$1 seconds=$2; shift 2
  for _ in $(seq 1 "$seconds"); do "$@" && return 0; sleep 1; done
  die "Timed out waiting for $what."
}

up() {
  for tool in cast forge jq curl docker cargo; do command -v "$tool" >/dev/null || die "Missing $tool. See README.md for what the local testnet needs."; done

  # ---- chain
  if chain_up; then
    say "chain      using the node at $RPC"
  else
    [ "$LOCAL" = 1 ] || die "No chain node answers at $RPC."
    say "chain      starting anvil at $RPC (log: tests/.run/anvil.log)"
    nohup "$ROOT/testnet/anvil.sh" >"$RUN/anvil.log" 2>&1 &
    echo $! >"$RUN/anvil.pid"
    started anvil
    wait_for "anvil" 30 chain_up
  fi
  [ "$(cast chain-id --rpc-url "$RPC")" = 31337 ] || die "The chain at $RPC is not a local testnet (chain id 31337). The tests sign with anvil's public keys and will not touch anything else."

  # ---- contracts
  # Deployed, and from the contracts as they are now: the tests would otherwise run the app against an older
  # protocol. What was deployed is remembered by the hash of the sources and the escrow it produced.
  CONTRACTS_NOW="$(cd "$ROOT/contracts" && find src script -name '*.sol' -print0 | sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1)"
  if [ -f "$DEPLOYMENT" ] && [ "$(cast code --rpc-url "$RPC" "$(jq -er .RideEscrow "$DEPLOYMENT")")" != 0x ] \
     && [ "$(cat "$RUN/contracts.deployed" 2>/dev/null)" = "$CONTRACTS_NOW $(jq -er .RideEscrow "$DEPLOYMENT")" ]; then
    say "contracts  already deployed ($(basename "$DEPLOYMENT"))"
  else
    say "contracts  deploying this code's contracts and seeding the test accounts"
    RPC_URL="$RPC" "$ROOT/testnet/deploy.sh" >"$RUN/deploy.log" 2>&1 || die "Deploy failed. See tests/.run/deploy.log"
    echo "$CONTRACTS_NOW $(jq -er .RideEscrow "$DEPLOYMENT")" >"$RUN/contracts.deployed"
  fi

  # ---- map pack
  if [ ! -f "$PACK/manifest.json" ]; then
    say "map pack   building $REGION with Docker: about 15 minutes, once (log: tests/.run/mappack.log)"
    "$ROOT/mappack/build.sh" "$REGION" >"$RUN/mappack.log" 2>&1 || die "The map pack did not build. See tests/.run/mappack.log"
  fi
  RPC_URL="$RPC" DEPLOYMENT="$DEPLOYMENT" "$ROOT/testnet/publish-pack.sh" "$PACK" >"$RUN/publish-pack.log" 2>&1 || die "Could not activate the map pack. See tests/.run/publish-pack.log"
  say "map pack   $REGION active on chain"

  # ---- gossip, mirror: the app looks for both on the chain node's host
  if [ "$LOCAL" = 1 ]; then
    # Always through Compose: it rebuilds the node when the core changed and does nothing otherwise.
    docker compose -f "$ROOT/testnet/docker-compose.yml" ps --status running --services 2>/dev/null | grep -qx gossip || started gossip
    docker compose -f "$ROOT/testnet/docker-compose.yml" up -d --build gossip >"$RUN/gossip.log" 2>&1 || die "The gossip node did not start. See tests/.run/gossip.log"
    wait_for "the gossip node" 60 listening 127.0.0.1 4001
    say "gossip     bootstrap node on port 4001"
    if listening 127.0.0.1 4002; then
      say "mirror     using the pack mirror on port 4002"
    else
      nohup "$ROOT/mappack/serve.sh" >"$RUN/mirror.log" 2>&1 &
      echo $! >"$RUN/mirror.pid"
      started mirror
      wait_for "the pack mirror" 30 listening 127.0.0.1 4002
      say "mirror     serving packs on port 4002"
    fi
  else
    listening "$HOST" 4001 || die "No gossip bootstrap node at $HOST:4001. The app looks for it next to the chain node."
    listening "$HOST" 4002 || die "No pack mirror at $HOST:4002. The app looks for it next to the chain node."
    say "gossip     using $HOST:4001, mirror $HOST:4002"
  fi
  PACK_ID=$(cast keccak "0x$(od -An -tx1 -v "$PACK/manifest.json" | tr -d ' \n')")
  curl -sf -m 5 -o /dev/null "http://$HOST:4002/$PACK_ID/manifest.json" \
    || die "The mirror on port 4002 does not have this pack ($PACK_ID). Restart it: make serve-packs"

  # ---- router
  if valhalla_up; then
    say "router     using Valhalla at $VALHALLA_URL"
  else
    [ -f "$BUILD/valhalla/valhalla.json" ] || die "No Valhalla config in $BUILD/valhalla. Build the pack here (make mappack), or point KLIPPELGO_VALHALLA at a Valhalla serving its tiles."
    docker rm -f "$VALHALLA_CONTAINER" >/dev/null 2>&1 || true
    docker run -d --name "$VALHALLA_CONTAINER" -u "$(id -u):$(id -g)" -p 127.0.0.1:8002:8002 -v "$BUILD":/data \
      "$VALHALLA_IMAGE" valhalla_service /data/valhalla/valhalla.json 2 >/dev/null
    started valhalla
    wait_for "Valhalla" 60 valhalla_up
    say "router     Valhalla on the pack's tiles, port 8002"
  fi

  # ---- phones: the first is the one most tests use; PHONES=3 adds two more for the tests with three apps
  [ -x "$ADB" ] && [ -x "$EMULATOR" ] || die "No Android SDK at $ANDROID_HOME. Run 'make android-two-phones' once: it installs the SDK, the emulator and the NDK."
  [ "$DEPLOYMENT" -ef "$ROOT/deployments/local.json" ] || die "The app is built with deployments/local.json. Put the deployment there to test against it."
  local mode=headless
  [ "${HEADLESS:-1}" = 1 ] || mode=window
  for i in $(seq 1 "$PHONES"); do
    start_phone "$i" "$mode"
  done
  for i in $(seq 1 "$PHONES"); do
    local serial; serial="$(serial_of "$i")"
    "$ADB" -s "$serial" wait-for-device
    wait_for "$serial to boot" 300 phone_up "$serial"
    # The tests wipe the app again and again, and the UI agent lets adb drive the screen. That is for an
    # emulator, never for a phone someone uses.
    if [ "$("$ADB" -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ] && [ "${KLIPPELGO_ALLOW_DEVICE:-0}" != 1 ]; then
      die "$serial is not an emulator. The tests wipe the app's data and install an agent that can drive the screen. If this is a device kept for testing, say so: KLIPPELGO_ALLOW_DEVICE=1"
    fi
    # A just-started emulator sometimes comes up with a network that reaches nothing. Switching it off and on
    # brings it back; without this the app only says the chain is out of reach.
    for attempt in 1 2 3 4; do
      phone_reaches_chain "$serial" && break
      [ "$attempt" = 4 ] && die "$serial cannot reach the chain node's host ($PHONE_HOST). Restart it: tests/stack.sh down, then up."
      "$ADB" -s "$serial" shell 'svc wifi disable; svc data disable; sleep 2; svc wifi enable; svc data enable' >/dev/null 2>&1 || true
      sleep 8
    done
    say "phone $i    $serial"
  done

  # ---- the app, with the addresses of this deployment built in, and its UI agent
  say "app        building"
  (cd "$ROOT/apps/android" && ./gradlew -q :app:assembleDebug :app:assembleDebugAndroidTest) >"$RUN/build.log" 2>&1 || die "The app did not build. See tests/.run/build.log"
  for i in $(seq 1 "$PHONES"); do
    local serial; serial="$(serial_of "$i")"
    # Leave the app first: the emulator's graphics can crash when the app is replaced with the map on screen.
    "$ADB" -s "$serial" shell input keyevent KEYCODE_HOME >/dev/null
    "$ADB" -s "$serial" install -r -t "$ROOT/apps/android/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
    "$ADB" -s "$serial" install -r -t "$ROOT/apps/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" >/dev/null
    say "app        installed on $serial"
  done

  # ---- the harness and its CLI
  (cd "$HERE" && ../apps/android/gradlew -q installDist) >>"$RUN/build.log" 2>&1 || die "The test harness did not build. See tests/.run/build.log"
  say
  say "Ready. Run the tests with tests/run.sh, or use the app by hand: tests/klippelgo help"
}

down() {
  "$HERE/klippelgo" peers stop >/dev/null 2>&1 || true
  [ -f "$RUN/started" ] || { say "Nothing to stop: this script started nothing."; return; }
  while read -r what; do
    case "$what" in
      emulator) "$ADB" -s "emulator-$EMULATOR_PORT" emu kill >/dev/null 2>&1 || true ;;
      emulator-*) "$ADB" -s "$what" emu kill >/dev/null 2>&1 || true; rm -f "$RUN/$what.mode" ;;
      valhalla) docker rm -f "$VALHALLA_CONTAINER" >/dev/null 2>&1 || true ;;
      gossip) docker compose -f "$ROOT/testnet/docker-compose.yml" stop gossip >/dev/null 2>&1 || true ;;
      anvil|mirror) [ -f "$RUN/$what.pid" ] && kill "$(cat "$RUN/$what.pid")" 2>/dev/null || true; rm -f "$RUN/$what.pid" ;;
    esac
    say "stopped $what"
  done < <(sort -u "$RUN/started")
  rm -f "$RUN/started"
}

status() {
  chain_up && say "chain      up at $RPC" || say "chain      down ($RPC)"
  listening "$HOST" 4001 && say "gossip     up" || say "gossip     down"
  listening "$HOST" 4002 && say "mirror     up" || say "mirror     down"
  valhalla_up && say "router     up" || say "router     down"
  for i in 1 2 3; do
    phone_up "$(serial_of "$i")" && say "phone $i    up as $(serial_of "$i")" || say "phone $i    down ($(serial_of "$i"))"
  done
}

case "${1:-}" in
  up) up ;;
  down) down ;;
  status) status ;;
  *) sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
