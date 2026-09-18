(ns io.github.getcolors.valkey.validate-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [green.cli :as green-cli]
            [io.github.getcolors.valkey.validate :as validate]))

(def fixture-file "test/fixtures/colors.yml")
(def optout-file "test/fixtures/optout.yml")

(defn- read-fixture [path overrides]
  (merge (green-cli/read-state path (str/replace (slurp path) "WORKDIR" ".colors"))
         overrides))
(defn fixture [& {:as overrides}] (read-fixture fixture-file overrides))
(defn optout [& {:as overrides}] (read-fixture optout-file overrides))


(def all-fixtures [fixture optout])
(deftest application-fixtures-and-backends
  (doseq [f all-fixtures] (is (= [] (validate/state-errors (f))))))
(deftest invalid-application-settings
  (doseq [[k v] [[:valkey-image "valkey/valkey:latest"] [:valkey-port 0]
                 [:valkey-port 65536] [:valkey-version "9.1"] [:valkey-version "latest"] [:valkey-backup-retention-days 0]
                 [:valkey-backup-max-age-hours -1] [:valkey-backup-r2-endpoint "http://example.test"]]]
    (is (seq (validate/state-errors (fixture k v))))))
(deftest credentials-and-identity
  (is (seq (validate/env-errors {"COLORS_PAR_PROFILE" "wrong"})))
  (is (= 2 (count (validate/secret-errors (fixture) :create))))
  (is (empty? (validate/secret-errors (fixture) :delete)))
  (is (validate/keygen? (fixture)))
  (is (not (validate/keygen? (optout)))))

(deftest rendered-settings-reject-shell-and-config-injection
 (doseq [payload ["$(touch /tmp/injected)" "`id`" "\"\nExecStart=/bin/false" "x\\y" "x\ny"]
         key [:valkey-backup-r2-bucket :valkey-backup-r2-region :valkey-backup-oncalendar]]
  (is (seq (validate/state-errors (fixture key payload))) (str key)))
 (doseq [endpoint ["https://$(id).example.test" "https://`id`.example.test"
                   "https://example.test/path" "https://example.test\"" "https://user:pass@example.test"]]
  (is (seq (validate/state-errors (fixture :valkey-backup-r2-endpoint endpoint)))))
 (doseq [image [(str "docker.io/valkey/$(id)@sha256:" (apply str (repeat 64 "a")))
                (str "docker.io/valkey/valkey:9.1.2`id`@sha256:" (apply str (repeat 64 "a")))]]
  (is (seq (validate/state-errors (fixture :valkey-image image))))))
