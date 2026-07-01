(ns kotoba.lang.checkpointer.dagcbor
  "Definite-length dag-cbor (the AT Protocol / @atproto/lex-cbor profile of
  RFC 8949 CBOR) encode/decode, WITH CID-link (tag 42) support -- the one
  feature `kotoba-lang/dag-cbor`'s `cbor.core` intentionally does not have
  (it's a namespace-only, zero-transitive-deps CBOR codec with no concept of
  content-addressed links). MST nodes and checkpoint 'ref' records embed CID
  links (the `l`/`v`/`t`/`blob` fields), so this port needs the tag-42 shape;
  rather than reach into `cbor.core`'s private encode-into (or fork it), this
  namespace is a small, self-contained, independently-verified extension of
  the same profile -- see README 'MST/CAR verification' for the follow-up
  note on whether to upstream tag-42 support into `kotoba-lang/dag-cbor`
  itself.

  Encoding profile (matches @atproto/lex-cbor's `encodeOptions` exactly, see
  node_modules/@atproto/lex-cbor/src/encoding.ts + node_modules/cborg at the
  time this was ported):
    - map keys sorted dag-cbor canonical style: shorter key first, then
      bytewise (this is `cborg`'s default `mapSorter`, NOT its stricter
      `rfc8949MapSorter` -- @atproto/lex-cbor's `encodeOptions` never
      overrides `mapSorter`, so the library default applies).
    - integers must be safe/exact (no floats) -- same constraint dag-cbor's
      own `cbor.core` enforces.
    - a CID link is CBOR tag 42 wrapping a byte string whose first byte is
      0x00 (historical prefix) followed by the CID's raw bytes
      (version+codec+multihash).
    - byte strings (major type 2), UTF-8 text (major type 3), arrays (major
      type 4, order-preserving), maps (major type 5), null/true/false.
  No indefinite lengths, no floats, no other tags -- the same tight profile
  `cbor.core` implements, plus tag 42.

  Every byte-level claim above is cross-checked against @atproto/lex-cbor's
  ACTUAL output in test/kotoba/lang/checkpointer/mst_vectors.edn (generated
  by scripts/gen-mst-vectors.mjs from this repo's own installed
  @atproto/repo + @atproto/lex-cbor -- never hand-typed)."
  (:require [multiformats.core :as mf])
  (:import (java.io ByteArrayOutputStream ByteArrayInputStream)
           (java.util Arrays)))

;; ---------------------------------------------------------------------------
;; CID link wrapper -- an explicit, unambiguous marker (unlike @atproto's
;; structural `ifCid` duck-typing) since we build every value ourselves.
;; ---------------------------------------------------------------------------

(defrecord CidLink [cid])

(defn cid-link
  "Wrap a CIDv1 string (e.g. \"bafyrei...\") so `encode` emits it as a
  tag-42 dag-cbor link instead of a plain string."
  [cid-str]
  (->CidLink cid-str))

(defn cid-link? [x] (instance? CidLink x))

;; ---------------------------------------------------------------------------
;; encode
;; ---------------------------------------------------------------------------

(defn- write-head [^ByteArrayOutputStream o major n]
  (let [mt (bit-shift-left (long major) 5)]
    (cond
      (< n 24) (.write o (int (bit-or mt n)))
      (< n 0x100) (do (.write o (int (bit-or mt 24))) (.write o (int n)))
      (< n 0x10000) (do (.write o (int (bit-or mt 25)))
                        (.write o (int (bit-and (bit-shift-right n 8) 0xff)))
                        (.write o (int (bit-and n 0xff))))
      (< n 0x100000000) (do (.write o (int (bit-or mt 26)))
                            (doseq [s [24 16 8 0]] (.write o (int (bit-and (bit-shift-right n s) 0xff)))))
      :else (do (.write o (int (bit-or mt 27)))
                (doseq [s [56 48 40 32 24 16 8 0]]
                  (.write o (int (bit-and (unsigned-bit-shift-right (long n) s) 0xff))))))))

(defn- key-bytes ^bytes [k]
  (.getBytes ^String (cond (string? k) k (keyword? k) (name k) :else (str k)) "UTF-8"))

(defn- dag-cbor-key< [a b]
  (let [^bytes ka (key-bytes a) ^bytes kb (key-bytes b)]
    (if (not= (alength ka) (alength kb))
      (< (alength ka) (alength kb))
      (loop [i 0]
        (cond (= i (alength ka)) false
              (not= (bit-and (aget ka i) 0xff) (bit-and (aget kb i) 0xff))
              (< (bit-and (aget ka i) 0xff) (bit-and (aget kb i) 0xff))
              :else (recur (inc i)))))))

(declare encode-into)

(defn- encode-pairs [^ByteArrayOutputStream o pairs]
  (write-head o 5 (count pairs))
  (doseq [[k v] pairs]
    (encode-into o (if (keyword? k) (name k) k))
    (encode-into o v)))

(defn- encode-cid-link [^ByteArrayOutputStream o ^CidLink link]
  (let [^bytes raw (mf/cid->bytes (:cid link))
        wrapped (byte-array (inc (alength raw)))]
    (System/arraycopy raw 0 wrapped 1 (alength raw))
    (write-head o 6 42)                     ; tag(42)
    (write-head o 2 (alength wrapped))       ; byte string, len = 1 + |raw cid|
    (.write o wrapped)))

(defn- encode-into [^ByteArrayOutputStream o x]
  (cond
    (nil? x) (.write o 0xf6)
    (true? x) (.write o 0xf5)
    (false? x) (.write o 0xf4)
    (cid-link? x) (encode-cid-link o x)
    (integer? x) (if (neg? x) (write-head o 1 (- (- x) 1)) (write-head o 0 x))
    (string? x) (let [b (.getBytes ^String x "UTF-8")] (write-head o 3 (count b)) (.write o b))
    (keyword? x) (let [b (.getBytes (name x) "UTF-8")] (write-head o 3 (count b)) (.write o b))
    (bytes? x) (do (write-head o 2 (count x)) (.write o ^bytes x))
    (map? x) (encode-pairs o (sort-by key dag-cbor-key< (seq x)))
    (sequential? x) (do (write-head o 4 (count x)) (doseq [e x] (encode-into o e)))
    :else (throw (ex-info "dagcbor: unsupported type" {:type (type x) :value x}))))

(defn encode
  "Deterministic dag-cbor bytes for a Clojure value (nil/bool/int/string/
  byte-array/sequential/map/CidLink). Map keys sorted dag-cbor canonical
  style (shorter-then-bytewise) -- exactly what @atproto/lex-cbor's `encode`
  produces for the same logical value."
  ^bytes [x]
  (let [o (ByteArrayOutputStream.)] (encode-into o x) (.toByteArray o)))

;; ---------------------------------------------------------------------------
;; decode (round-trip convenience; the runtime read paths in this sidecar
;; never need it -- payloads are spooled as raw bytes, never re-parsed from
;; MST/CAR -- but it's cheap, useful for tests, and completes the codec).
;; ---------------------------------------------------------------------------

(defn- read-n [^ByteArrayInputStream in cnt]
  (loop [i 0 acc 0] (if (< i cnt) (recur (inc i) (bit-or (bit-shift-left acc 8) (.read in))) acc)))

(defn- read-arg [^ByteArrayInputStream in info]
  (cond (< info 24) info
        (= info 24) (.read in)
        (= info 25) (read-n in 2)
        (= info 26) (read-n in 4)
        (= info 27) (read-n in 8)
        :else (throw (ex-info "dagcbor: indefinite/reserved length unsupported" {:info info}))))

(declare decode-from)

(defn- read-bytes ^bytes [^ByteArrayInputStream in n]
  (let [b (byte-array n)] (.read in b 0 n) b))

(defn- cid-string-of-raw ^String [^bytes raw] (str "b" (mf/base32 raw)))

(defn- decode-cid-link [^bytes b]
  (when (or (zero? (alength b)) (not= 0 (aget b 0)))
    (throw (ex-info "dagcbor: invalid CID bytes (expected leading 0x00)" {})))
  (->CidLink (cid-string-of-raw (Arrays/copyOfRange b 1 (alength b)))))

(defn- decode-from [^ByteArrayInputStream in]
  (let [ib (.read in)]
    (when (neg? ib) (throw (ex-info "dagcbor: unexpected end of input" {})))
    (let [major (bit-shift-right ib 5) info (bit-and ib 0x1f)]
      (case (int major)
        0 (read-arg in info)
        1 (- (- (read-arg in info)) 1)
        2 (read-bytes in (read-arg in info))
        3 (String. (read-bytes in (read-arg in info)) "UTF-8")
        4 (vec (repeatedly (read-arg in info) #(decode-from in)))
        5 (into {} (repeatedly (read-arg in info) #(let [k (decode-from in)] [k (decode-from in)])))
        6 (let [tag (read-arg in info)]
            (when (not= tag 42) (throw (ex-info "dagcbor: unsupported tag" {:tag tag})))
            (let [v (decode-from in)]
              (when-not (bytes? v) (throw (ex-info "dagcbor: tag 42 must wrap a byte string" {})))
              (decode-cid-link v)))
        7 (case (int info) 20 false 21 true 22 nil
              (throw (ex-info "dagcbor: unsupported simple/float" {:info info})))
        (throw (ex-info "dagcbor: unsupported major type" {:major major}))))))

(defn decode
  "Decode dag-cbor bytes -> Clojure data. Maps -> {} (string keys), arrays ->
  [], text -> String, byte-strings -> ^bytes, ints -> Long, true/false/nil,
  tag-42 links -> CidLink."
  [^bytes b]
  (decode-from (ByteArrayInputStream. b)))

;; ---------------------------------------------------------------------------
;; CID of a value's canonical encoding (codec 0x71 dag-cbor, sha2-256).
;; ---------------------------------------------------------------------------

(defn cid-for-value
  "dag-cbor CIDv1 string of `x`'s canonical encoding (matches
  @atproto/lex-data's `cidForCbor(encode(x))`)."
  ^String [x]
  (mf/cidv1-dag-cbor (encode x)))
