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

This is a **physical move only** (TypeScript unchanged) — no dedicated
tests existed for this module in `etzhayyim-sdk` to bring along, and a CLJC
port is deferred to a later, separate task.

**`dist/` is committed** (see `kotoba-lang/pqh`'s README for the rationale
— git-dependency consumers in `allow-scripts`-gated environments never run
the `prepare` build step).

## Development

```bash
npm install
npm run build
```

## License

Apache 2.0 + Charter Compliance Rider v3.6 (`/CHARTER-RIDER.md`).
