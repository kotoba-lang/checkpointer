#!/usr/bin/env node
/**
 * etzhayyim-checkpointer — sidecar launcher.
 * Per ADR-2605171800 (revise) Stage 2.
 */
import {runFromEnv} from "./checkpointer.js";

async function main(): Promise<void> {
  const sidecar = await runFromEnv();
  const shutdown = async (sig: NodeJS.Signals): Promise<void> => {
    console.error(`[checkpointer] received ${sig}, stopping…`);
    await sidecar.stop();
    process.exit(0);
  };
  process.on("SIGTERM", shutdown);
  process.on("SIGINT", shutdown);
  console.error("[checkpointer] sidecar listening");
}

main().catch((err: unknown) => {
  console.error("[checkpointer] fatal:", err);
  process.exit(1);
});
