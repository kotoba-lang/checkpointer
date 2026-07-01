#!/usr/bin/env node
/**
 * etzhayyim-checkpointer — sidecar launcher.
 * Per ADR-2605171800 (revise) Stage 2.
 */
import { runFromEnv } from "./checkpointer.js";
async function main() {
    const sidecar = await runFromEnv();
    const shutdown = async (sig) => {
        console.error(`[checkpointer] received ${sig}, stopping…`);
        await sidecar.stop();
        process.exit(0);
    };
    process.on("SIGTERM", shutdown);
    process.on("SIGINT", shutdown);
    console.error("[checkpointer] sidecar listening");
}
main().catch((err) => {
    console.error("[checkpointer] fatal:", err);
    process.exit(1);
});
