(ns kotoba.lang.checkpointer.keystore
  "Per-cell AEAD symmetric-key persistence:
  <state-dir>/keys/<url-encoded cell_did>.key, lazy-generate-on-first-use,
  0700 dir / 0600 file, atomic write -- mirrors checkpointer.ts's
  #getOrCreateCellKey. I/O only; no crypto logic (that's
  kotoba.lang.checkpointer.crypto, which is pure given key bytes)."
  (:require [kotoba.lang.checkpointer.fsutil :as fs]
            [kotoba.lang.pqh.crypto :as pqh]
            [clojure.java.io :as io])
  (:import (java.io File)
           (java.net URLEncoder)
           (java.nio.file Files)))

(defn- url-encode ^String [^String s] (URLEncoder/encode s "UTF-8"))

(defn- keys-dir ^File [state-dir] (io/file (str state-dir) "keys"))

(defn- key-file ^File [state-dir cell-did]
  (io/file (keys-dir state-dir) (str (url-encode cell-did) ".key")))

(defn load-or-create-cell-key!
  "Returns the cell's 32-byte AEAD key, persisting a freshly-generated one on
  first use. Throws if an existing on-disk key has the wrong length."
  ^bytes [state-dir cell-did]
  (fs/ensure-dir! (keys-dir state-dir) "rwx------")
  (let [f (key-file state-dir cell-did)]
    (if (.exists f)
      (let [k (Files/readAllBytes (.toPath f))]
        (when (not= (alength k) pqh/KEY-BYTES)
          (throw (ex-info (str "[checkpointer] cell key for " cell-did " has wrong length: " (alength k))
                           {:cell-did cell-did :length (alength k)})))
        k)
      (let [fresh (pqh/generate-key)]
        (fs/atomic-write-bytes! f fresh "rw-------")
        fresh))))
