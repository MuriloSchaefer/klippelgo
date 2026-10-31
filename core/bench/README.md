# klippelgo-bench: will it hold?

A benchmark of what KlippelGo's shared services carry as the number of drivers and riders grows. It answers
two questions, and keeps every answer so the next change can be compared against it:

1. **The model**: given N drivers and M riders, how much does the busiest hour put on the chain, the bundler,
   RPC nodes, gossip routers, pack mirrors and each phone, against what one of each can take?
2. **The gossip load test**: does the real router deliver a busy cell's traffic to the phones that need it,
   and how fast? It runs `klippelgo-node` as its own process and real `GossipNode`s as phones, exactly the
   code the app runs. Only the people are simulated: when they ask, quote, lock and drive.

```sh
make bench-model                                # the model at 100 000 drivers and 1 000 000 riders, instantly
make bench-model ARGS="--drivers 5000 --riders 50000 --cells 40"
make bench-gossip                               # the stress run: about 1.5 minutes
make bench                                      # stress, light, then the model with the light run's figures
core/target/release/klippelgo-bench compare core/bench/runs/gossip-A.json core/bench/runs/gossip-B.json
core/target/release/klippelgo-bench params      # every parameter, its default and where it comes from
```

Build in release, as `make` does: a debug build's phones and router are too slow and lose messages that a
release build delivers, so its figures say nothing about the network.

Every run is saved to `core/bench/runs/` (git ignores it) with the parameters it used and the commit it ran on.
`compare` prints the figures that matter side by side, which got better or worse, and which parameters differ.
`make bench` hands the model the run in `core/bench/runs/gossip-latest.json`, so do not pass it `--out`.

## The scenario

Every number is a parameter (`--name value`; `params` lists them). Those marked *code* are constants of the app
or the core today: when the code changes one, change its default here. Those marked *assumed* are guesses
about people and about services KlippelGo does not run; they are the first thing to argue about.

| | Default | |
| --- | --- | --- |
| Drivers, riders | 100 000, 1 000 000 | |
| Drivers online in the busiest hour | 50% | assumed |
| Rides per rider per day, share in the busiest hour | 0.5, 10% | assumed: 13.9 new rides a second |
| Populated geohash-5 cells, busiest over average | 300, ×4 | assumed: the busiest has 667 drivers and a ride every 5.4 s |
| Asking, accept, pickup, trip | 20 s, 10 s, 5 min, 15 min | assumed |
| Announce, positions, request lifetime, inbox, list cap | 2.5 s; 4 s and 0.9 s; 10 min; 4096; 200 | code |
| Chain | Polygon PoS: 45 M gas per 2 s block, 25% of it for KlippelGo | assumed |

## How the load test scales down

A cell at the target scale has hundreds of drivers, more phones than one computer runs. So the test runs a
square of `grid`×`grid` cells (default 3×3) at the busiest cell's **full traffic**, and shares each cell's
people among a few phones: `driver_nodes` and `rider_nodes` per cell. A phone still hears exactly what one
real phone there hears, so what one phone receives is measured at full scale. The router serves fewer phones
than it would; its CPU per delivery is measured and the model multiplies it out.

It runs in two standard ways, which `make bench` does one after the other:

| Run | Phones per cell | What it measures |
| --- | --- | --- |
| **stress** (the default) | 20 drivers, 5 riders | Whether messages reach their phones when a cell has more subscribers than GossipSub's mesh. The figure to improve |
| **light** (`--driver-nodes 1 --rider-nodes 1`) | 1 and 1 | Under the mesh limit: what one phone receives at full traffic, and the router's cost per delivery, which the model uses |

The run begins with the last twenty minutes' rides already under way, then measures for `duration_s` after
`warmup_s`. Phones drain their inbox every 2 s and hold at most 200 requests until they expire, as
`Matchmaker` does, so a request lost to a full inbox or a full list counts as not seen.

### What a run reports

| Figure | Meaning |
| --- | --- |
| Drivers who saw a request while it was open | Of the driver phones that hear the request's cell, the share that had it on screen before the rider locked |
| Offers that reached their rider in time | Of the offers drivers sent, the share the rider's phone had before choosing |
| Routes, positions | Share delivered to the one phone each is for |
| Time to first see it | From the first announcement to the phone's drain: includes the app's 2 s cadence |
| Driver phone: messages a second (model) | Measured, and what the model says a phone should receive |
| Largest drain | Most messages one drain returned; at 4096 the inbox is dropping messages |
| Router: CPU seconds per million deliveries | What the model uses to say how many router cores the target needs |
| Load generator | If it falls behind or uses most cores, the computer is the limit: lower `driver_nodes`, `rider_nodes` or `grid` |

`klippelgo-bench probe --phones 20 --rate 5 [--size 900] [--publishers 2]` is the smallest question about the
router, without rides: phones on one topic, each publishing `rate` messages a second, and the share each
receives. Use it to tell a network problem from a ride-model one. `RUST_LOG=libp2p_gossipsub=debug` shows what
the phones' GossipSub does (GRAFT, PRUNE, IHAVE); the router's own logs are not captured.

## What it found, 2026-10-24

At 100 000 drivers and 1 000 000 riders the system does not hold. In order of how hard each is to fix:

| Area | Finding | Kind |
| --- | --- | --- |
| Gossip | **Delivery collapses past about a dozen phones per cell.** At the target traffic, 10 phones on a cell receive 100%; with 19, 2% of drivers see a request; with 46 or more, none do. The router forwards eagerly only to its GossipSub mesh (6 to 12 phones per topic), the others get lazy gossip that cannot keep up with tens of messages a second, and phones only connect to the router, so nobody else forwards. A busy cell at the target has about 6 000 phones. Router CPU is not the limit (0.2 cores) | Design: phones must reach more than one forwarder, or cells need far fewer subscribers |
| Phone | **A driver's phone would receive 1 400 to 2 600 messages a second, about 7.5 to 13.7 GB an hour.** 90% of the bytes are positions and sealed routes of other people's rides, sent to the whole cell, the routes again every 2.5 s for ten minutes. The 4096-message inbox overflows every drain, and the 200-request list fills with closed requests kept for 10 minutes | Design: apps S5, S6, S8 |
| Chain | 18.6 M gas a second: 83% of Polygon PoS's whole block space, 3.3× the 25% assumed available | Design: a rollup or per-region escrows |
| RPC | Each phone on screen reads every escrow event of the last 17 blocks every 2 s: 1 900 logs, 2.4 GB an hour per phone; 232 000 requests and 44 GB a second across phones | Mostly design: filter by topic, stop re-reading, push instead of poll |
| Bundler, mirrors, routers | 56 operations a second; 80 TB per pack release; about 50 GB a second of gossip | Scale out, once the above is fixed |

Smaller scales show where each breaks first: `make bench-model ARGS="--drivers 2000 --riders 20000"`.

## Changing the benchmark

How the code is laid out and what a run does step by step is [core/docs/benchmark.md](../docs/benchmark.md).

- When the app or core changes a cadence, a size or a cap, change the parameter's default in
  [`src/params.rs`](src/params.rs) and say so in the commit: older runs then differ in that parameter, and
  `compare` shows it.
- A new shared service or a new limit is a new row in [`src/model.rs`](src/model.rs), with the documented
  lever it points to.
- A new kind of gossip message is a new `Kind` in [`src/gossip.rs`](src/gossip.rs), sent where the app sends
  it, and a new figure in `CellTraffic`.
