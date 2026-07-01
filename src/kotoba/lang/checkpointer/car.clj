(ns kotoba.lang.checkpointer.car
  "CAR v1 file writer -- varint-length-prefixed dag-cbor header + a stream of
  varint-length-prefixed (CID-bytes ++ content-bytes) blocks, ported from
  @atproto/repo's `src/car.ts` (`writeCarStream`/`blocksToCarFile`). The
  length-prefix varint is the standard unsigned LEB128 the `varint` npm
  package (and `multiformats.core/varint`) both implement -- reused as-is,
  no CAR-specific quirks."
  (:require [kotoba.lang.checkpointer.dagcbor :as cbor]
            [kotoba.lang.checkpointer.blockmap :as bm]
            [multiformats.core :as mf])
  (:import (java.io ByteArrayOutputStream)))

(defn- car-header-bytes ^bytes [root-cid]
  (cbor/encode {:version 1 :roots (if root-cid [(cbor/cid-link root-cid)] [])}))

(defn blocks->car-bytes
  "root-cid: a CID string, or nil for a rootless CAR. blockmap: a
  kotoba.lang.checkpointer.blockmap/BlockMap, written in its insertion
  order (matches `blocksToCarFile`'s block ordering exactly)."
  ^bytes [root-cid blockmap]
  (let [o (ByteArrayOutputStream.)
        ^bytes header (car-header-bytes root-cid)]
    (.write o ^bytes (mf/varint (alength header)))
    (.write o header)
    (doseq [[cid content] (bm/bm-entries blockmap)]
      (let [^bytes cid-bytes (mf/cid->bytes cid)
            ^bytes content-bytes content
            len (+ (alength cid-bytes) (alength content-bytes))]
        (.write o ^bytes (mf/varint len))
        (.write o cid-bytes)
        (.write o content-bytes)))
    (.toByteArray o)))
