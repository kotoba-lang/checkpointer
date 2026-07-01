import { readdir } from "node:fs/promises";
export declare const PROTOCOL_VERSION: 1;
export type Op = "put" | "get_tuple" | "list" | "put_writes" | "anchor_pending" | "anchor_commit" | "health";
export interface Request {
    v: typeof PROTOCOL_VERSION;
    op: Op;
    cell_did: string;
    thread_id: string;
    checkpoint_ns: string;
    checkpoint_id: string | null;
    payload: Uint8Array | null;
    meta: Record<string, unknown>;
}
export interface Response {
    ok: boolean;
    mst_root_cid: string | null;
    data: Uint8Array | null;
    error: string | null;
}
export interface SaverIndexRow {
    cell_did: string;
    thread_id: string;
    checkpoint_ns: string;
    checkpoint_id: string;
    mst_root_cid: string;
    car_size_bytes: number;
    car_blob_count: number;
    mst_projected_at: number;
    ipfs_pinned_at: number | null;
    ipfs_pin_service: string | null;
    ipfs_pin_id: string | null;
    anchor_tx_hash: `0x${string}` | null;
    anchor_block_number: number | null;
    anchor_log_index: number | null;
    anchor_chain_id: number;
    anchored_at: number | null;
}
export interface SidecarConfig {
    socketPath?: string;
    stateDir?: string;
    allowedDids: ReadonlySet<string>;
    ipfsApiUrl?: string;
    anchorChainId?: number;
    blobInlineThreshold?: number;
    /**
     * Cell DIDs whose payloads MUST be encrypted at rest before MST projection
     * (per ADR-2605181100 hard rule on confidentiality). For each listed
     * cell_did, a per-cell symmetric key is lazy-generated and persisted to
     * `<stateDir>/keys/<encodeURIComponent(cell_did)>.key`. Payloads are then
     * XChaCha20-Poly1305-AEAD'd before they hit the MST / CAR / IPFS pipeline.
     *
     * Cells not in this set continue to write plaintext to MST — appropriate
     * for the open-* public substrate, prohibited for any private data.
     */
    encryptCells?: ReadonlySet<string>;
}
type ResolvedConfig = Required<Omit<SidecarConfig, "ipfsApiUrl" | "encryptCells">> & Pick<SidecarConfig, "ipfsApiUrl"> & {
    encryptCells: ReadonlySet<string>;
};
export declare class CheckpointerSidecar {
    #private;
    readonly cfg: ResolvedConfig;
    constructor(cfg: SidecarConfig);
    start(): Promise<void>;
    stop(): Promise<void>;
}
export declare function encode(value: unknown): Uint8Array;
export declare function decode(bytes: Uint8Array): unknown;
export declare function runFromEnv(): Promise<CheckpointerSidecar>;
export declare const _internal: {
    readdir: typeof readdir;
};
export {};
