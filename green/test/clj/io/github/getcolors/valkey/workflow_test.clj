(ns io.github.getcolors.valkey.workflow-test
 (:require [clojure.test :refer [deftest is testing]] [clojure.java.io :as io] [clojure.string :as str]
           [green.workflow :as engine] [io.github.getcolors.valkey.workflow :as workflow]
           [io.github.getcolors.valkey.tools :as tools] [io.github.getcolors.valkey.compute :as compute]
           [io.github.getcolors.compute-orchestration :as orchestration]
           [io.github.getcolors.compute-inspection :as inspection]
           [io.github.getcolors.compute-managed-backend :as managed-backend]
           [io.github.getcolors.valkey.validate-test :refer [fixture optout all-fixtures]]))
(defn- route [event opts start] (loop [step start path [step]] (let [[_ next] (workflow/wire-fn step (assoc opts :green/event event))] (if next (recur next (conj path next)) path))))
(deftest cleanup-order-and-read-only-events
 (is (= [tools/load-infrastructure-step :valkey/ansible] (workflow/wire-fn :valkey/load-infrastructure {:green/event :delete})))
 (is (= [tools/ansible-local-step :valkey/infrastructure] (workflow/wire-fn :valkey/ssh-config {:green/event :delete})))
 (is (= [tools/infrastructure-step] (workflow/wire-fn :valkey/infrastructure {:green/event :delete})))
 (doseq [event [:rehearse :describe]]
  (is (= :valkey/load-infrastructure (second (workflow/wire-fn :valkey/start {:green/event event}))))))
(deftest failures-refuse-application-inventory
 (with-redefs [orchestration/orchestrate (fn [& _] {:status "error"})]
  (is (= 1 (:green/exit (tools/infrastructure-step (fixture :green/event :create))))))
 (with-redefs [inspection/read-deployment (fn [_ env deps _] (is (map? env)) (is (contains? env "HOME")) (is (= {} deps)) {:status "error"})]
  (is (= 1 (:green/exit (tools/load-infrastructure-step (fixture :green/event :delete))))))
 (is (thrown? Exception (compute/node (fixture :green/event :create)))))
(deftest inspection-preserves-connection-user-and-identity
 (let [node {:node_id "0" :provider "vultr" :name "valkey-fixture" :ip "203.0.113.8" :user "ubuntu" :sudoer "ubuntu" :ssh_identity_file "/operator/key"}]
  (with-redefs [inspection/read-deployment (fn [& _] {:status "present" :cluster {:nodes [node]}})]
   (let [out (tools/load-infrastructure-step (fixture :green/event :describe))]
    (is (= "ubuntu" (:user out))) (is (= "/operator/key" (:ssh-private-key-path out)))
    (is (= "203.0.113.8" (:ip (compute/node out))))))))
(deftest destroyed-delete-stops-cleanup
 (with-redefs [inspection/read-deployment (fn [& _] {:status "destroyed"})]
  (is (:valkey/already-destroyed (tools/load-infrastructure-step (fixture :green/event :delete))))
  (is (= 1 (:green/exit (tools/load-infrastructure-step (fixture :green/event :describe)))))))
(deftest lifecycle-order
 (is (= [:valkey/start :valkey/infrastructure :valkey/ssh-config :valkey/ansible :valkey/acceptance]
        (route :create (fixture) :valkey/start)))
 (is (= [:valkey/start :valkey/load-infrastructure :valkey/ansible :valkey/ssh-config :valkey/infrastructure]
        (route :delete (fixture) :valkey/start))))
(deftest dry-runs-and-backup-credentials
 (doseq [f all-fixtures event [:create :delete :rehearse :describe]]
  (is (= 0 (:green/exit (workflow/start-step (f :green/event event :green/dry-run true) {})))))
 (is (= 2 (:green/exit (workflow/start-step (fixture :green/event :create) {})))))
(deftest repeat-delete-and-unreadable-state
 (with-redefs [inspection/read-deployment (fn [& _] {:status "destroyed"})]
  (let [r (tools/load-infrastructure-step (fixture :green/event :delete))]
   (is (= [] (workflow/next-fn :valkey/load-infrastructure [:valkey/ansible] r)))))
 (doseq [status ["error" "absent"]]
  (with-redefs [inspection/read-deployment (fn [& _] {:status status})]
   (is (= 1 (:green/exit (tools/load-infrastructure-step (fixture :green/event :delete))))))))
(deftest fixtures-build-with-normalized-node
 (doseq [f all-fixtures]
  (let [directory (.toFile (java.nio.file.Files/createTempDirectory "valkey-build-" (make-array java.nio.file.attribute.FileAttribute 0)))]
   (try
    (let [result (engine/run workflow/workflow (f :green/event :build :workdir (.getPath directory)))]
     (is (not (engine/failed? result)) (:green/err result))
     (is (= "192.0.2.10" (:ip (compute/node result))))
     (is (not (.exists (io/file directory "valkey-storage")))))
    (finally (doseq [file (reverse (file-seq directory))] (.delete file)))))))

(deftest managed-backend-lifecycle-is-delegated
 (let [opts (fixture :s3-bucket-mode "managed" :green/event :delete)]
  (is (= :valkey/backend-finalize (last (route :delete opts :valkey/start))))
  (doseq [status ["destroyed" "absent"]]
   (with-redefs [inspection/read-deployment (fn [& _] {:status status})]
    (let [r (tools/load-infrastructure-step opts)]
     (is (= 0 (:green/exit r)))
     (is (= [:valkey/backend-finalize] (mapv first (workflow/next-fn :valkey/load-infrastructure [:valkey/ansible] r)))))))
  (with-redefs [inspection/read-deployment (fn [& _] {:status "error"})]
   (let [r (tools/load-infrastructure-step opts)]
    (is (:valkey/finalize-only r))
    (is (= [:valkey/backend-finalize] (mapv first (workflow/next-fn :valkey/load-infrastructure [:valkey/ansible] r))))))
  (doseq [status ["destroyed" "absent" "skipped" "refused"]]
   (with-redefs [managed-backend/finalize-backend! (fn [& _] {:status status})]
    (is (= (if (= "refused" status) 1 0) (:green/exit (workflow/backend-finalize-step opts))))))
  (with-redefs [managed-backend/finalize-backend! (fn [& _] (throw (Exception. "private backend diagnostics")))]
   (let [r (workflow/backend-finalize-step opts)]
    (is (= 1 (:green/exit r)))
    (is (not (str/includes? (:green/err r) "private backend diagnostics")))))))
