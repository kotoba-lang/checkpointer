(ns kotoba.lang.checkpointer.pin-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.lang.checkpointer.pin :as pin]
            [kotoba.lang.ipfs :as ipfs]))

(defn- fake-http [result-or-ex]
  (reify ipfs/IHttp
    (-get [_ _url] {:status 200 :body (byte-array 0)})
    (-post [_ _url] {:status 200 :body (byte-array 0)})
    (-post-file [_ _url _content]
      (if (instance? Throwable result-or-ex)
        (throw result-or-ex)
        {:status 200 :body (.getBytes (str "{\"Hash\":\"" (:cid result-or-ex) "\",\"Size\":\"" (:size result-or-ex) "\"}") "UTF-8")}))))

(deftest pin-soon-success-test
  (testing "on-result is called with the parsed {:cid :size} on success"
    (let [http (fake-http {:cid "bafyPinned" :size 123})
          result (promise)]
      @(pin/pin-soon! http "http://kubo" (byte-array [1 2 3]) (fn [r] (deliver result r)))
      (is (= {:cid "bafyPinned" :size 123} (deref result 2000 :timeout))))))

(deftest pin-soon-failure-swallowed-test
  (testing "on failure, on-result is called with nil (error logged, not thrown to caller)"
    (let [http (fake-http (ex-info "boom" {}))
          result (promise)]
      @(pin/pin-soon! http "http://kubo" (byte-array [1 2 3]) (fn [r] (deliver result r)))
      (is (nil? (deref result 2000 :timeout))))))

(deftest pin-soon-noop-without-api-url-test
  (testing "no-ops (returns nil, never calls on-result) when ipfs-api-url is nil"
    (let [called (atom false)]
      (is (nil? (pin/pin-soon! (fake-http {:cid "x" :size 1}) nil (byte-array [1]) (fn [_] (reset! called true)))))
      (Thread/sleep 50)
      (is (false? @called)))))
