(ns kotoba.lang.checkpointer.spool
  "Filesystem spooling of CAR + payload bytes to
  <state-dir>/queue/<url-encoded cell_did>/<checkpoint_id>.{car,payload},
  atomic via .tmp+rename -- mirrors checkpointer.ts's #spoolCar/#spoolPayload/
  #loadPayload exactly. Pure I/O (no dispatch/business logic); tests exercise
  this against a `java.nio.file.Files/createTempDirectory` temp dir, never a
  fixed path."
  (:require [kotoba.lang.checkpointer.fsutil :as fs]
            [clojure.java.io :as io])
  (:import (java.io File)
           (java.net URLEncoder)
           (java.nio.file Files)))

(defn- url-encode ^String [^String s] (URLEncoder/encode s "UTF-8"))

(defn cell-queue-dir ^File [state-dir cell-did]
  (io/file (str state-dir) "queue" (url-encode cell-did)))

(defn spool-car! [state-dir cell-did checkpoint-id ^bytes car-bytes]
  (let [dir (fs/ensure-dir! (cell-queue-dir state-dir cell-did))]
    (fs/atomic-write-bytes! (io/file dir (str checkpoint-id ".car")) car-bytes)))

(defn spool-payload! [state-dir cell-did checkpoint-id ^bytes payload-bytes]
  (let [dir (fs/ensure-dir! (cell-queue-dir state-dir cell-did))]
    (fs/atomic-write-bytes! (io/file dir (str checkpoint-id ".payload")) payload-bytes)))

(defn load-payload
  ^bytes [state-dir cell-did checkpoint-id]
  (Files/readAllBytes (.toPath (io/file (cell-queue-dir state-dir cell-did) (str checkpoint-id ".payload")))))
