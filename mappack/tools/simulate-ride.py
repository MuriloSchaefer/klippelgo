#!/usr/bin/env python3
"""Invents one ride on a running Valhalla: a route, the GPS log each phone would record driving it, and the
driver's log map-matched back onto the road network.

    mappack/tools/simulate-ride.py <ride.json> [seed] [west,south,east,north] [valhalla url]

Then, from core/, turn it into signed evidence and check it as a juror would:

    cargo run -p klippelgo-proofs --example simulate_ride -- <pack dir> <ride.json> <evidence.json>
    cargo run -p klippelgo-proofs --bin klippelgo-juror -- <pack dir> <evidence.json>

The logs are the route's shape walked at 36 km/h with a fix every 2 seconds and a few metres of noise.
"""
import json
import math
import random
import sys
import time
import urllib.error
import urllib.request

out = sys.argv[1]
random.seed(int(sys.argv[2]) if len(sys.argv) > 2 else 1)
west, south, east, north = map(float, (sys.argv[3] if len(sys.argv) > 3 else "-53.55,-25.03,-53.37,-24.89").split(","))
url = sys.argv[4] if len(sys.argv) > 4 else "http://127.0.0.1:8002"
ATTRIBUTES = ["edge.way_id", "edge.begin_shape_index", "edge.end_shape_index", "shape"]
SPEED = 10.0  # m/s
EVERY = 2.0  # s
START = 1_800_000_000_000  # ms


def wait_for_valhalla():
    """Valhalla takes a moment to load its tiles after the container starts."""
    for _ in range(60):
        try:
            urllib.request.urlopen(f"{url}/status", timeout=2)
            return
        except OSError:
            time.sleep(0.5)
    sys.exit(f"No Valhalla at {url}")


def post(action, body):
    request = urllib.request.Request(f"{url}/{action}", data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    try:
        return json.load(urllib.request.urlopen(request, timeout=30))
    except urllib.error.HTTPError as error:
        return json.load(error)


def decode(shape):
    points, i, lat, lon = [], 0, 0, 0
    while i < len(shape):
        for axis in range(2):
            shift = value = 0
            while True:
                byte = ord(shape[i]) - 63
                i += 1
                value |= (byte & 0x1F) << shift
                shift += 5
                if byte < 0x20:
                    break
            delta = ~(value >> 1) if value & 1 else value >> 1
            if axis == 0:
                lat += delta
            else:
                lon += delta
        points.append((lat / 1e6, lon / 1e6))
    return points


def metres(a, b):
    dy = (b[0] - a[0]) * 111_195
    dx = (b[1] - a[1]) * 111_195 * math.cos(math.radians(a[0]))
    return math.hypot(dx, dy)


def drive(shape, noise):
    """A fix every EVERY seconds along the shape, each off by about `noise` metres."""
    fixes, t, carried = [], 0.0, 0.0
    step = SPEED * EVERY

    def fix(point, speed):
        lat = point[0] + random.gauss(0, noise) / 111_195
        lon = point[1] + random.gauss(0, noise) / (111_195 * math.cos(math.radians(point[0])))
        fixes.append({"t": START + round(t * 1000), "lat": round(lat * 1e7), "lon": round(lon * 1e7),
                      "accuracyCm": round(noise * 250), "speedCmS": round(speed * 100)})

    fix(shape[0], 0)
    for a, b in zip(shape, shape[1:]):
        length = metres(a, b)
        at = step - carried
        while at <= length:
            t += EVERY
            fix((a[0] + (b[0] - a[0]) * at / length, a[1] + (b[1] - a[1]) * at / length), SPEED)
            at += step
        carried = (carried + length) % step
    t += EVERY
    fix(shape[-1], 0)
    return fixes


wait_for_valhalla()
for _ in range(50):
    costing = random.choice(["auto", "motorcycle"])
    points = [{"lat": random.uniform(south, north), "lon": random.uniform(west, east)} for _ in range(2)]
    route = post("route", {"locations": points, "costing": costing, "units": "kilometers", "directions_type": "none"})
    if "trip" in route and route["trip"]["summary"]["length"] > 1:
        break
else:
    sys.exit("Valhalla found no route between 50 random pairs of points")

leg = route["trip"]["legs"][0]
filters = {"attributes": ATTRIBUTES, "action": "include"}
attributes = post("trace_attributes", {"encoded_polyline": leg["shape"], "shape_match": "walk_or_snap", "costing": costing, "filters": filters})
shape = decode(leg["shape"])
rider, driver = drive(shape, 4.0), drive(shape, 3.0)
matched = post("trace_attributes", {
    "shape": [{"lat": f["lat"] / 1e7, "lon": f["lon"] / 1e7, "time": f["t"] / 1000} for f in driver],
    "shape_match": "map_snap",
    "costing": costing,
    "trace_options": {"gps_accuracy": 10, "search_radius": 40},
    "filters": filters,
})
ride = {"vehicle": "car" if costing == "auto" else "motorcycle", "route": route, "attributes": attributes,
        "riderFixes": rider, "driverFixes": driver, "matched": matched}
with open(out, "w") as file:
    json.dump(ride, file)
print(f"{leg['summary']['length']} km by {ride['vehicle']}, {len(driver)} fixes per phone")
