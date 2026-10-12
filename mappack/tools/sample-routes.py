#!/usr/bin/env python3
"""Samples random routes from a running Valhalla, to check that they translate into paths the verifier accepts.

    mappack/tools/sample-routes.py <out dir> <count> [west,south,east,north] [valhalla url]

Start Valhalla on the pack's tiles first (see mappack/README.md), then run

    cargo run --release -p klippelgo-proofs --example measure_many -- <pack dir> <out dir>

from core/. Each sample holds Valhalla's route and its trace_attributes for that route's shape.
"""
import json
import os
import random
import sys
import time
import urllib.error
import urllib.request

out, count = sys.argv[1], int(sys.argv[2])
west, south, east, north = map(float, (sys.argv[3] if len(sys.argv) > 3 else "-53.55,-25.03,-53.37,-24.89").split(","))
url = sys.argv[4] if len(sys.argv) > 4 else "http://127.0.0.1:8002"
ATTRIBUTES = ["edge.way_id", "edge.begin_shape_index", "edge.end_shape_index", "shape"]


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


os.makedirs(out, exist_ok=True)
wait_for_valhalla()
random.seed(7)
routed = 0
for i in range(count):
    costing = random.choice(["auto", "motorcycle"])
    points = [{"lat": random.uniform(south, north), "lon": random.uniform(west, east)} for _ in range(2)]
    route = post("route", {"locations": points, "costing": costing, "units": "kilometers", "directions_type": "none"})
    if "trip" not in route:
        continue
    attributes = post("trace_attributes", {
        "encoded_polyline": route["trip"]["legs"][0]["shape"],
        "shape_match": "walk_or_snap",
        "costing": costing,
        "filters": {"attributes": ATTRIBUTES, "action": "include"},
    })
    sample = {"vehicle": "car" if costing == "auto" else "motorcycle", "route": route, "attributes": attributes}
    with open(f"{out}/{i:04}.json", "w") as file:
        json.dump(sample, file)
    routed += 1
print(f"{routed} routes, {count - routed} pairs with no route")
