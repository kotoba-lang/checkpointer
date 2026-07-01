(ns kotoba.lang.checkpointer.vectors
  "Cross-language known-answer test vectors, generated programmatically from
  this repo's own npm deps (@atproto/repo, @atproto/lex-cbor, @atproto/lex-
  data, @msgpack/msgpack) -- see mst_vectors.edn (checked in alongside this
  namespace) and scripts/gen-mst-vectors.mjs for the generator these vectors
  came from. Regenerate with:
    node scripts/gen-mst-vectors.mjs > test/kotoba/lang/checkpointer/mst_vectors.edn"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def vectors
  (edn/read-string (slurp (io/resource "kotoba/lang/checkpointer/mst_vectors.edn"))))

(defn hex->bytes ^bytes [^String s]
  (let [n (count s)]
    (byte-array (for [i (range 0 n 2)]
                  (unchecked-byte (Integer/parseInt (subs s i (+ i 2)) 16))))))

(defn bytes->hex ^String [^bytes b]
  (str/join (map #(format "%02x" (bit-and (int %) 0xff)) b)))
