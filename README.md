# checkpointer

`@etzhayyim/checkpointer` — a TypeScript sidecar implementing the LangGraph
`MstCheckpointSaver` wire protocol over a Unix socket. A Python
`MstCheckpointSaver` sends msgpack-framed requests here; this sidecar
projects each checkpoint payload to an atproto-shaped MST, builds a
deterministic CAR, returns the root CID synchronously, and enqueues the CAR
for IPFS pin + L2 anchor.

Depends on `@etzhayyim/ipfs` (blob pin) and `@etzhayyim/pqh/crypto` (AEAD
envelope for at-rest encryption) as sibling `kotoba-lang` packages instead
of local relative imports.

## Provenance

Relocated 2026-07-01 from `etzhayyim/root:20-actors/etzhayyim-sdk/src/
{checkpointer,checkpointer-bin}.ts` to `kotoba-lang/checkpointer` per the
org-taxonomy library-placement rule (any library/substrate code belongs in
`kotoba-lang`, ADR-2606302300 / ADR-2607011830). Design authority remains
ADR-2605171800 (revise) Stages 1-2 and ADR-2605172100, both in
`etzhayyim/root`.

Zero etzhayyim-specific coupling beyond two default config values
(`socketPath: "/run/etzhayyim/checkpointer.sock"`, `stateDir: ~/.etzhayyim/
checkpointer`) — both are ordinary overridable defaults, not hardcoded
constants.

The initial relocation was a **physical move only** (TypeScript unchanged,
no dedicated tests existed in `etzhayyim-sdk` to bring along). A Clojure
port has since landed (see below) and is the canonical implementation for
new JVM/Clojure consumers going forward; the TypeScript implementation
(`src/*.ts` + committed `dist/`) is unchanged and remains the
npm-consumable artifact for existing Node-based deployments.

**`dist/` is committed** (see `kotoba-lang/pqh`'s README for the rationale
— git-dependency consumers in `allow-scripts`-gated environments never run
the `prepare` build step).

## Clojure port

`src/kotoba/lang/checkpointer/*.clj` is a full port of the sidecar,
namespace-per-concern under `kotoba.lang.checkpointer.*`:
`dagcbor` / `blockmap` / `mst` / `car` / `commit` (the MST+CAR pipeline) /
`msgpack` / `crypto` / `keystore` / `index` / `spool` / `fsutil` / `pin` /
`http-jdk` / `dispatch` (pure op-dispatch core) / `sidecar` (Unix-socket
server) / `cli` (env-var resolution + entrypoint, mirrors
`checkpointer-bin.ts`).

**JVM-only (`.clj`, not `.cljc`) scope decision.** This sidecar is a
long-lived daemon process (a launchd/systemd/K8s-CronJob equivalent), not a
browser artifact — matching `kotoba-lang/witness-quorum`/`pqh`/`base-l2`'s
precedent, no CLJS branch was attempted. `kotoba.lang.pqh.crypto` (the AEAD
dependency) is itself JVM-only for the same class of reason (Bouncy Castle,
no Web Crypto XChaCha20-Poly1305 coverage) as documented in `pqh`'s own
README.

**The MST/CAR determinism contract, and how it was verified.**
`checkpointer.ts`'s `#commitMst` carries an explicit, load-bearing
correctness contract: *same (cell_did, checkpoint_id, payload bytes) => same
rootCid, bit for bit* — a silent divergence here is a content-addressing
correctness bug, not a crash, and was treated as the highest-risk part of
this whole port. No pure-Clojure MST-algorithm or CAR-serialization library
existed anywhere in `kotoba-lang` at the time of this port, so three
building blocks were composed:

- **`kotoba.lang.checkpointer.dagcbor`** — a definite-length dag-cbor codec
  WITH CBOR tag-42 CID-link support (`{cid: ...}` -> tag(42) + a byte string
  prefixed with `0x00` + the CID's raw bytes), matching
  `@atproto/lex-cbor`'s exact encoding profile (canonical map-key sort:
  shorter-key-first then bytewise; safe-integers-only; no floats/no other
  tags). `kotoba-lang/dag-cbor` (`cbor.core`) does everything BUT the CID
  link (it's an intentionally link-free, zero-transitive-dep codec) — MST
  nodes and the checkpoint `ref` record both embed CID links (`l`/`v`/`t`/
  `blob`), so this namespace is a small, independently-verified extension
  of the same profile rather than a fork or a private-internals reach-in.
  Whether to upstream tag-42 support into `kotoba-lang/dag-cbor` itself is
  a reasonable follow-up, not done here to avoid touching a shared
  dependency mid-port.
- **`kotoba.lang.checkpointer.mst`** — the Merkle Search Tree itself
  (node/leaf representation, `add` with layer-promotion/subtree-splitting,
  `getUnstoredBlocks` diffing), a full port of `@atproto/repo`'s
  `src/mst/mst.ts` (read directly from this repo's own installed
  `node_modules`, not from memory). The **layer/fanout math**
  (`leadingZerosOnHash`'s 2-bit-group leading-zero count over SHA-256(key),
  and `countPrefixLen`'s key-prefix-compression helper) is NOT
  reimplemented here — it's delegated to the already-existing
  `io.github.kotoba-lang/mst` (`mst.core`, a separate shared repo), reused
  directly rather than writing a second copy of load-bearing hash math.
  One discrepancy was found and NOT silently adopted: `mst.core/key-valid?`
  caps MST keys at 256 chars, but the real `@atproto/repo`
  `isValidMstKey` (`src/mst/util.ts`) caps at 1024 — using the 256 cap
  would reject valid long checkpoint IDs the real system accepts, so this
  port keeps its own 1024-correct `valid-mst-key?` instead (see
  `kotoba.lang.checkpointer.mst`'s namespace docstring); the 256-vs-1024
  gap looks like a bug worth fixing upstream in `kotoba-lang/mst`.
- **`kotoba.lang.checkpointer.car`** — CAR v1 file writer (varint-prefixed
  header + CID-addressed block stream), a port of `@atproto/repo`'s
  `src/car.ts`, built on `kotoba-lang/multiformats` for CIDs/varints/base32
  (which already had the needed `cidv1-dag-cbor` — codec `0x71` — function,
  no extension needed there).

**Verification discipline**: every byte-level claim above was checked
against THIS repo's own installed `@atproto/repo` / `@atproto/lex-cbor` /
`@atproto/lex-data`, called directly via `node -e`/a generator script
(`scripts/gen-mst-vectors.mjs` -> `test/kotoba/lang/checkpointer/
mst_vectors.edn`) — never hand-typed. Cases covered, all byte-for-byte
(root CID *and* full CAR bytes, not just the CID):
  - an **empty tree** (`mst_empty`);
  - a **single-entry tree** via the full `commit-mst` pipeline, both the
    plain-payload path (`commit_single_small`, blobCount=0) and the
    >16 KiB blob-ref-indirection path (`commit_single_large_blobref`,
    blobCount=1, two blocks);
  - a **deliberately multi-layer tree** (`mst_multi_entry_split`, searched
    via the real `leadingZerosOnHash` for a mix of layer-0/1/2 keys so the
    final tree has `root_layer=2` — i.e. genuine `createChild`/
    `createParent`/`splitAround` structure, not just a flat run of leaves).

  No gap was found requiring a documented shortfall — every vector matched
  byte-for-byte on the first algorithmically-correct attempt (see
  `test/kotoba/lang/checkpointer/{dagcbor,mst,commit}_test.clj`).

**msgpack**: no pure-Clojure msgpack library existed in `kotoba-lang`
either. `kotoba.lang.checkpointer.msgpack` is hand-rolled (covers nil/bool/
int-all-widths-incl-full-uint64-via-BigInteger/float32(decode-only)/
float64/str/bin/array/map; no extension types, which this protocol never
uses) rather than pulling in a JVM library (e.g. `org.msgpack:msgpack-core`)
— msgpack's format is materially simpler than CBOR's (no canonical-sort, no
link concept), and this sidecar's own deployment model (long-lived JVM
daemon, not a `bb` script) removes the one reason a Java dependency might
have been preferred. Unlike the MST/CAR layer, msgpack here is a WIRE
PROTOCOL with no content-addressing determinism contract — the correctness
bar is round-trip fidelity + interop with a real msgpack implementation
(Python's `msgpack`, `@msgpack/msgpack`), not byte-identical output, though
byte-identity is what's actually achieved for the representative value
shapes checked (`test/kotoba/lang/checkpointer/msgpack_vectors.edn`'s
`:msgpack_vectors`, generated the same way via `@msgpack/msgpack`).

**Unix-domain-socket server**: JDK 16+'s native support
(`java.net.UnixDomainSocketAddress` + `ServerSocketChannel.open(
StandardProtocolFamily/UNIX)`) — zero extra dependency, verified with a
standalone spike before building `kotoba.lang.checkpointer.sidecar` around
it. This is deliberately NOT behind an injectable protocol the way the IPFS
HTTP transport is (`kotoba.lang.ipfs/IHttp`, reused here with a new JDK
`HttpClient`-backed reference adapter, `kotoba.lang.checkpointer.http-jdk`,
including a minimal multipart/form-data encoder for Kubo's `/api/v0/add`) —
running a Unix-socket server IS this process's job, not an abstractable I/O
seam. What IS kept independently testable is everything around the socket:
`kotoba.lang.checkpointer.dispatch/handle-request` is a plain
Request-map-in/Response-map-out function, directly callable in tests with
an injected `env` (fake/temp-dir-backed collaborators, see
`dispatch_test.clj`) with no socket involved at all, plus a true end-to-end
test (`sidecar_test.clj`) that starts a real Unix-socket server, connects a
real client, sends a framed msgpack `put` request, and confirms the
response's `mst_root_cid` matches `commit-mst` computed directly for the
same input.

## Development

TypeScript:

```bash
npm install
npm run build
```

Clojure:

```bash
clj-kondo --lint src test
clojure -M:test
```

**`dist/` is committed** — git-dependency consumers in `allow-scripts`-gated
environments never run the `prepare` build step. CI rebuilds and diffs
`dist/` on every push; after any `src/*.ts` change, run `npm run build` and
commit the updated `dist/` in the same commit.

## License

Apache 2.0 + Charter Compliance Rider v3.6 (`/CHARTER-RIDER.md`).
