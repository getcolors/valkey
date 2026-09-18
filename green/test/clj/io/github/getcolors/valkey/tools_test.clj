(ns io.github.getcolors.valkey.tools-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [io.github.getcolors.valkey.tools :as tools]
            [io.github.getcolors.valkey.validate :as validate]
            [io.github.getcolors.valkey.validate-test :refer [fixture optout]]))

(defn- spec-for [opts file]
  (some #(when (str/ends-with? (str (:target %)) file) %) (tools/ansible-specs opts)))

(deftest the-backup-prefix-is-namespaced-by-profile
  ;; Two deployments sharing a bucket must never share a prefix.
  (is (= "valkey-fixture/valkey" (tools/set-prefix (fixture :green/event :build)))))

(deftest inventory-keeps-one-target-and-no-private-address
  (let [inv (json/parse-string (tools/inventory (assoc (fixture :green/event :build) :ip "192.0.2.10")) true)
        host (get-in inv [:all :children :valkey :hosts :valkey-fixture])]
    (is (= "192.0.2.10" (:ansible_host host)))
    (is (= "root" (:ansible_user host)))
    (is (nil? (:vpc_ip host)))))

(deftest a-build-inventory-carries-the-placeholder-only
  (let [inv (tools/inventory (fixture :green/event :build))]
    (is (str/includes? inv tools/placeholder-ip))
    (is (not (str/includes? inv "10.60.")))))

(deftest ansible-renders-the-whole-tree
  (let [targets (map #(str (:target %)) (tools/ansible-specs (fixture :green/event :build)))]
    (doseq [f ["ansible.cfg" "main.yml" "cleanup.yml" "rehearsal.yml" "compose.yml"
               "r2-env.sh" "valkey-backup.sh" "valkey-restore-check.sh"
               "valkey-smoke.sh" "valkey-monitor.sh" "valkey-status.sh" "inventory.json"]]
      (is (some #(str/ends-with? % f) targets) f))
    (is (= (count tools/ansible-files) (count (distinct tools/ansible-files))))))

(deftest operator-secrets-reach-the-host-as-lookups-not-values
  ;; `.colors/` is generated output and the goldens are committed, so the
  ;; secret must never be the thing that lands on disk — the expression is.
  (let [template (slurp (io/resource "io/github/getcolors/valkey/tools/ansible/main.yml"))]
    (doseq [par ["COLORS_PAR_VALKEY_BACKUP_R2_ACCESS_KEY_ID"
                 "COLORS_PAR_VALKEY_BACKUP_R2_SECRET_ACCESS_KEY"]]
      (is (str/includes? template (str "lookup('env','" par "')")) par))))

(deftest the-data-map-carries-no-operator-secret
  (let [data (:data (spec-for (fixture :green/event :build :valkey-backup-r2-access-key-id "test-only-key" :valkey-backup-r2-secret-access-key "test-only-secret") "main.yml"))]
    (is (= "valkey-fixture/valkey" (:valkey-backup-set-prefix data)))
    (doseq [k [:valkey-backup-r2-access-key-id :valkey-backup-r2-secret-access-key]]
      (is (nil? (get data k)) (str k))))
)

(defn- temp-workdir []
  (str (java.nio.file.Files/createTempDirectory "valkey-test-" (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest the-play-runner-mirrors-the-sdk-step
  (let [runs (atom [])
        workdir (temp-workdir)
        runner (fn [exit out] (fn [args opts _] (swap! runs conj [args (:extra-env opts)]) {:exit exit :out out :err ""}))
        recap "PLAY RECAP\nvalkey-fixture : ok=3 changed=1 unreachable=0 failed=0 skipped=0 rescued=0 ignored=0\n"]
    (testing "a build renders and runs nothing"
      (with-redefs [green.process/run-with-timeout (runner 0 recap)]
        (is (= 0 (:green/exit (tools/run-play (fixture :green/event :build :workdir workdir) "main.yml" true))))
        (is (empty? @runs))))
    (testing "a create runs the play with host-key checking off and parses the recap"
      (with-redefs [green.process/run-with-timeout (runner 0 recap)]
        (let [r (tools/run-play (fixture :green/event :create :green/dry-run true :ip "192.0.2.10" :workdir workdir) "main.yml" true)]
          (is (= 0 (:green/exit r)))
          (is (= {"valkey-fixture" {:ok 3 :changed 1 :unreachable 0 :failed 0 :skipped 0 :rescued 0 :ignored 0}} (:ansible/recap r)))
          (is (= [["ansible-playbook" "-i" "inventory.json" "main.yml"] {"ANSIBLE_HOST_KEY_CHECKING" "False"}] (last @runs))))))
    (testing "a failure carries the play's output"
      (with-redefs [green.process/run-with-timeout (runner 2 "fatal: unreachable")]
        (let [r (tools/run-play (fixture :green/event :create :green/dry-run true :ip "192.0.2.10" :workdir workdir) "main.yml" true)]
          (is (= 2 (:green/exit r)))
          (is (str/includes? (:green/err r) "ansible-playbook main.yml failed: fatal: unreachable")))))
    (testing "a runtime timeout (negative exit) is a failure, never a pass"
      (with-redefs [green.process/run-with-timeout (runner -1 "")]
        (let [r (tools/run-play (fixture :green/event :create :green/dry-run true :ip "192.0.2.10" :workdir workdir) "main.yml" true)]
          (is (= 1 (:green/exit r)))
          (is (str/includes? (:green/err r) "ansible-playbook main.yml failed")))))))

(deftest the-compose-file-publishes-on-loopback-alone
  ;; Exposure is decided by what Compose publishes: one binding, loopback.
  (let [template (slurp (io/resource "io/github/getcolors/valkey/tools/ansible/compose.yml"))
        bindings (re-seq #"\"[^\"]*:<\{ valkey-port \}>:6379\"" template)]
    (is (= ["\"127.0.0.1:<{ valkey-port }>:6379\""] bindings))
    (is (not (str/includes? template "vpc")))))

(deftest the-play-and-the-smoke-gate-know-no-private-address
  (let [play (slurp (io/resource "io/github/getcolors/valkey/tools/ansible/main.yml"))
        smoke (slurp (io/resource "io/github/getcolors/valkey/tools/ansible/valkey-smoke.sh"))]
    (is (str/includes? play "valkey-smoke {{ ansible_host }}"))
    (is (not (str/includes? play "vpc")))
    (is (str/includes? smoke "expected=\"127.0.0.1:$port\""))
    (is (not (str/includes? smoke "vpc")))))

(deftest a-delete-without-compute-skips-the-host-entirely
  ;; There is no machine to stop, and the cleanup play would only fail against
  ;; the placeholder address.
  (is (= 0 (:green/exit (tools/ansible-step (assoc (fixture :green/event :build) :green/event :delete))))))

(deftest acceptance-is-skipped-outside-a-real-create
  (doseq [event [:build :delete :rehearse :describe]]
    (is (= 0 (:green/exit (tools/acceptance-step (assoc (fixture :green/event :build) :green/event event)))))))

(deftest cli-auth-is-environment-only
 (let [args (tools/valkey-args 20001 true "PING") calls (atom [])]
  (is (= ["valkey-cli" "--no-auth-warning" "-h" "127.0.0.1" "-p" "20001" "PING"] args))
  (with-redefs [babashka.process/process
                (fn [argv opts] (swap! calls conj [argv opts])
                  (doto (promise) (deliver {:exit 0 :out "PONG" :err ""}))) ]
   (is (= 0 (:exit (tools/run-quiet args {"VALKEYCLI_AUTH" "test-only-password"} 1000))))
   (tools/run-quiet (tools/valkey-args 20001 false "PING") {} 1000)
   (is (= {"PATH" (System/getenv "PATH") "VALKEYCLI_AUTH" "test-only-password"} (:env (second (first @calls)))))
   (is (= {"PATH" (System/getenv "PATH")} (:env (second (second @calls)))))
   (is (not (str/includes? (pr-str (map first @calls)) "test-only-password"))))))

(deftest the-tunnel-rides-the-generated-alias-and-the-configured-port
  (let [[_ _ script] (tools/tunnel-args (assoc (fixture :green/event :build) :valkey-port 6380) 20001)]
    (is (str/includes? script "-L 20001:127.0.0.1:6380 valkey-fixture"))
    (is (str/includes? script "ExitOnForwardFailure=yes"))))

(deftest public-port-probe-distinguishes-closure-from-failure
 (let [[exe flag script ip port] (tools/closed-port-args "203.0.113.5" 6379)]
  (is (= ["python3" "-c" "203.0.113.5" "6379"] [exe flag ip port]))
  (is (str/includes? script "timeout=5"))
  (is (str/includes? script "sys.exit(20)")))
 (doseq [exit [127 -1 1 20 nil]]
  (with-redefs [tools/read-remote-password (constantly "test-only")
                tools/run-quiet (fn [& _] {:exit exit :out "" :err ""})]
   (let [result (tools/acceptance-step (fixture :green/event :create :ip "203.0.113.5"))]
    (is (= 1 (:green/exit result)))
    (is (str/includes? (:green/err result) "closure is unverified")))))
 (with-redefs [tools/read-remote-password (constantly "test-only")
               tools/run-quiet (fn [& _] {:exit 0 :out "" :err ""})]
  (is (str/includes? (:green/err (tools/acceptance-step (fixture :green/event :create :ip "203.0.113.5"))) "accepted a connection"))))

(deftest local-play-receives-its-required-node-fields
  (with-redefs [green.ansible/ansible-with-spec
                (fn [opts config _]
                  (is (= [{:name "valkey-fixture" :ip "192.0.2.10" :user "root"}]
                         (get-in config [:extra-vars :ssh_hosts]))) opts)]
    (tools/ansible-local-step (fixture :green/event :build))))

(deftest compute-json-accepts-library-mixed-key-maps
  (is (= {"backups" true "region" "ams"}
         (cheshire.core/parse-string
          (#'io.github.getcolors.valkey.tools/compute-json {:region "ams" "backups" true} 0)))))

(deftest the-password-is-read-as-root-whoever-the-alias-logs-in-as
  ;; root on the Vultr and DigitalOcean images, ubuntu on the AWS AMI: the
  ;; plain read serves the first, the passwordless-sudo fallback the second.
  (let [seen (atom nil)]
    (with-redefs [tools/run-quiet (fn [args _ _] (reset! seen args) {:exit 0 :out "generated\n" :err ""})]
      (is (= "generated" (tools/read-remote-password (fixture))))
      (let [[ssh _ _ alias command] @seen]
        (is (= "ssh" ssh))
        (is (= "valkey-fixture" alias))
        (is (str/starts-with? command "cat /etc/valkey/secrets/password"))
        (is (str/includes? command "|| sudo -n cat /etc/valkey/secrets/password"))))
    (testing "a failed read is nil, never a partial reply"
      (with-redefs [tools/run-quiet (fn [& _] {:exit 1 :out "" :err "Permission denied"})]
        (is (nil? (tools/read-remote-password (fixture))))))))
