(ns kotoba.lang.checkpointer.keystore-test
  "`.clj`, genuinely JVM-only (not a compliance gap): exercises
  `kotoba.lang.checkpointer.keystore`'s real filesystem key persistence
  against a real temp dir -- keystore.clj itself is `.clj`-only I/O, see
  its namespace docstring."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [kotoba.lang.checkpointer.keystore :as keystore])
  (:import (java.net URLEncoder)
           (java.nio.file Files)))

(defn- temp-dir []
  (str (Files/createTempDirectory "checkpointer-keystore-test" (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest lazy-generate-and-persist-test
  (let [dir (temp-dir)
        k1 (keystore/load-or-create-cell-key! dir "did:key:zCell1")]
    (testing "generates a 32-byte key on first use"
      (is (= 32 (alength k1))))
    (testing "reloads the SAME key on a later call (persisted, not regenerated)"
      (let [k2 (keystore/load-or-create-cell-key! dir "did:key:zCell1")]
        (is (= (vec k1) (vec k2)))))
    (testing "a different cell_did gets a DIFFERENT key"
      (let [k3 (keystore/load-or-create-cell-key! dir "did:key:zCell2")]
        (is (not= (vec k1) (vec k3)))))))

(deftest wrong-length-key-throws-test
  (let [dir (temp-dir)
        keys-dir (io/file dir "keys")]
    (.mkdirs keys-dir)
    (spit (io/file keys-dir (str (URLEncoder/encode "did:key:zBad" "UTF-8") ".key")) "short")
    (testing "throws if an on-disk key has the wrong length"
      (is (thrown? Exception (keystore/load-or-create-cell-key! dir "did:key:zBad"))))))
