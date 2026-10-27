# Layer discovery and stalled sessions

## Evidence, 2026-10-26

Device logs at 17:47 showed a six-item tray, repeated scans with no move, then an
unconfirmed tap. Earlier frames included impossible transient tray labels such as
four identical types and a second tap before the previous animation had finished.
The subsequent captured board (`app/src/androidTest/assets/stuck-board.png`) has an
empty tray, so it is a detector fixture, not proof of the exact stalled position.

The previous tracker kept any tray-count decrease other than two forever. It also
counted unchanged observations as failed taps. Both are now replaced by a bounded
three-frame tray-consistency check: no planning during changes, and repeated
observations alone never count as gesture failures. Confirmation requires net +1
or -2 occupancy after settling, rather than any identity relabel.

## Research and adaptation

- T. Stam, *Solving Mahjong Solitaire Positions* (2007), section 3.3:
  https://iivq.net/scriptie/scriptie-bsc.pdf
  The MaxBlock heuristic considers how many tiles a move obstructs. We adapt the
  vertical dependency idea to seven-slot triple matching: reward removal of
  blockers and their descendants, discount distant descendants and later moves,
  and reduce the reward when tray capacity is low. The reward enters the actual
  search score, not only branch ordering.
- Michiel de Bondt, *Solving Mahjong Solitaire boards with peeking* (2012):
  https://arxiv.org/abs/1203.6559
  The practical search uses pruning and critical groups with known identities.
  Its exact pair-removal rules and guarantees do not apply to this screenshot-based
  triple game. We retain unknown buried cards as information targets and never
  simulate them as known matches.
- duule/SheepSolver source:
  https://github.com/duule/SheepSolver/blob/main/solver.py
  Its clickable test uses explicit layer numbers and overlap from map data.
  Our inputs have no layer numbers. We infer only supported front-to-back edges
  from bright cards overlapping dim/partially observed cards; vertical screen
  order is not evidence of stack depth.

## Vision changes and limitations

Visible side strips and incomplete silhouettes can propose a full card using the
median dimensions of observed cards. A proposal needs two perpendicular outer
edges outside the known covers before it enters the board as blocked. Matching a
covered face masks covering-card pixels and requires sufficient visible, varying
image content. Fully hidden faces and ambiguous dim-on-dim ordering remain unknown.

On-device fixture inspection also revealed that large dark speaker icons were
misclassified as dim cards. Availability now samples the paper rim, avoiding the
central icon. The fixture checks these speakers, isolated endgame-like cards,
locked lower cards, dependency edges, an empty tray, and an exposed planned move.

## Verification

Host tests cover settling and recovery from animated counts, no false failed-tap
counts, masking, partial geometry evidence, unknown-layer discovery scoring,
ambiguity safety, and legal capacity transitions. `CapturedBoardTest` runs the
real OpenCV mapper on the saved screenshot on the device without sending taps.
A complete autonomous winning round is not established by these tests.

## Resume, speed, and potential unlock routes

The session limit is now 20 minutes, including a bounded wait for the next frame.
Capture restarts in the same app process preserve sprite prototypes for the same
game package. Board and tray observations still settle afresh before tapping;
changing the target package resets the prototypes. Prototypes are not persisted
across process death. A cold-start tray sprite with no acceptable board match
gets its own identity instead of being forced onto the nearest unrelated type.

`UnlockPotential` searches the observed blocker graph for up to 12 targets with
up to eight ancestors, retaining at most 48 routes at each step. It simulates
blocker picks, intermediate triple clears, and the target pick, including peak
tray occupancy. Short, confident routes to a tray triple receive more credit than
expensive discoveries. Missing/cyclic dependencies and unknown intermediate
faces cannot establish a known-match route. Unknown targets receive only a
discovery bonus with spare tray capacity. The main beam search executes only the
first exposed move and reobserves; predictions never authorize tapping a still
locked face. This bounded heuristic is not a complete solver or a win guarantee.

Unlock analysis gets at most a quarter of the planner's time budget. Incomplete
beam iterations are discarded to avoid favoring the first root evaluated at a
deeper level. Candidate ordering scores each tile once. Recognition uses bounded,
exact-signature caching invalidated whenever prototypes change, and bulk OpenCV
pixel reads replace per-pixel JNI calls. Confirmation and tray-settling checks
remain enabled. The device fixture compares cached and uncached recognition
results and records warm median times without asserting unstable timing limits.

Patch validation: 71 host tests passed and both APKs built. The main APK was
installed on the test phone at 18:31:46 on 2026-10-26. Android rejected the updated
test APK twice with `INSTALL_FAILED_USER_RESTRICTED`. The old installed test
cannot exercise this build because its compiled constructor signatures are
outdated (`NoSuchMethodError`).

Retry at 18:41 on 2026-10-26: the updated test APK installed successfully. Fixed
the benchmark's inferred integer return type (`Log.i`) to explicit `Unit`, as
JUnit requires void test methods. Both on-device tests then passed. Warm median
recognition on the saved screenshot was 111 ms cached versus 122 ms uncached
(about 9% lower latency), with identical detections across all 16 paired scans.
This measures the cache on one repeated image, not end-to-end gameplay speed or
win rate; a complete winning round remains unverified.

## Regression: speculative variety and the last spare slot

The 18:48 device log shows a confirmed move taking the tray from
`[9, 1, 1, 3, 40]` to `[9, 1, 1, 3, 40, 26]`, followed by repeated scans with
no safe move. Logs do not contain enough tile detail to replay the exact board
or rule out recognition errors. Three synthetic regression cases reproduced
related planner defects: spending the last spare slot on unknown discovery,
trusting an ambiguous escape identity, and letting a large speculative stack
outweigh an immediately available triple.

The correction gives certain immediate triples priority, requires a known
identity-robust completion after a pick leaves six held items, and checks this
for every plausible identity of the picked card. Known blocker-to-triple routes
remain allowed. Discovery credit is capped and scaled down as the tray fills;
initial unlock credit decays with simulated depth. Plans are ranked by score
before depth, so merely extending a sequence is no longer the primary objective.
Unknown faces are still discovery targets, not guaranteed matches. A null plan
can pause for better observations; the guard does not promise recovery from an
already trapped board.

Verification of this correction: 76 host tests passed, including the three
previously failing cases, a valid last-slot unlock route, and a complete
nine-tile layered-board replanning test. Installed the rebuilt app and reran
both screenshot-based device tests successfully. No live win-rate comparison
has been performed.
