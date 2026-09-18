(ns io.github.getcolors.valkey.workflow
  (:require [green.cli :as green-cli]
            [green.dry-run :as dry-run]
            [green.lifecycle :as lifecycle]
            [green.progress :as progress]
            [green.workflow :as wf]
            [io.github.getcolors.compute-managed-backend :as managed-backend]
            [io.github.getcolors.valkey.ssh :as ssh]
            [io.github.getcolors.valkey.ssh-config :as ssh-config]
            [io.github.getcolors.valkey.tools :as tools]
            [io.github.getcolors.valkey.validate :as validate]))
(def defaults {:provider-compute validate/default-compute-provider
               :provider-backend "r2" :compute-prevent-destroy true
               :workdir ".colors"})
(defn start-step
  ([opts] (start-step opts (System/getenv)))
  ([opts env]
   (lifecycle/preflight
    opts {:defaults defaults :overlay green-cli/read-pars
          :validators [(fn [_ env _] (validate/env-errors env))
                       (fn [opts _ _] (validate/state-errors opts))
                       (fn [opts _ {:keys [event real?]}] (when real? (validate/secret-errors opts event)))
                       (fn [opts _ {:keys [event real?]}]
                         (when (and real? (= :delete event) (:compute-prevent-destroy opts))
                           ["compute destruction is protected; set COLORS_PAR_COMPUTE_PREVENT_DESTROY=false to delete"]))]
          :after-validate (fn [opts _ {:keys [event real?]}]
                            (if (and real? (= :create event)) (ssh-config/preflight! opts)
                                (assoc (if real? opts (ssh/with-machine-key opts)) :green/exit 0)))} env)))

(defn backend-finalize-step
  "Delete the managed S3 state bucket after everything in it has been
  destroyed. The library proves the bucket holds nothing but retired state
  before it removes anything; a refusal is an error, never a skipped step."
  [opts]
  (try
    (let [result (managed-backend/finalize-backend! opts (tools/environment opts))]
      (if (contains? #{"destroyed" "absent" "skipped"} (:status result))
        (assoc opts :green/exit 0)
        (assoc opts :green/exit 1 :green/err "managed backend finalization refused")))
    (catch Exception _ (assoc opts :green/exit 1 :green/err "managed backend finalization refused; live or unowned state remains"))))

(defn wire-fn
  "Create compute, then SSH config, application convergence and acceptance.
  Delete cleans the host and alias before compute and any managed backend."
  [step opts]
  (let [managed-backend? (tools/managed-backend? opts)]
    (case (:green/event opts)
      :delete (case step
                :valkey/start [start-step :valkey/load-infrastructure]
                :valkey/load-infrastructure [tools/load-infrastructure-step :valkey/ansible]
                :valkey/ansible [tools/ansible-step :valkey/ssh-config]
                :valkey/ssh-config [tools/ansible-local-step :valkey/infrastructure]
                :valkey/infrastructure (cond managed-backend? [tools/infrastructure-step :valkey/backend-finalize]
                                            :else [tools/infrastructure-step])
                :valkey/backend-finalize [backend-finalize-step])
      :rehearse (case step
                  :valkey/start [start-step :valkey/load-infrastructure]
                  :valkey/load-infrastructure [tools/load-infrastructure-step :valkey/rehearsal]
                  :valkey/rehearsal [tools/rehearsal-step])
      :describe (case step
                  :valkey/start [start-step :valkey/load-infrastructure]
                  :valkey/load-infrastructure [tools/load-infrastructure-step :valkey/describe]
                  :valkey/describe [tools/describe-step])
      (case step
        :valkey/start [start-step :valkey/infrastructure]
        :valkey/infrastructure [tools/infrastructure-step :valkey/ssh-config]
        :valkey/ssh-config [tools/ansible-local-step :valkey/ansible]
        :valkey/ansible [tools/ansible-step :valkey/acceptance]
        :valkey/acceptance [tools/acceptance-step]))))

(defn next-fn
  "Successors, with the two repeat-delete routes out of the inspection step:
  a finalized backend goes straight to the finalizer; a destroyed or absent
  machine skips the host and the compute destroy and continues with whatever
  managed stages the deployment has, or stops when it has none."
  [step successors opts]
  (cond
    (wf/failed? opts) []
    (and (= step :valkey/load-infrastructure) (:valkey/finalize-only opts)) [[:valkey/backend-finalize opts]]
    (and (= step :valkey/load-infrastructure) (:valkey/already-destroyed opts))
    (cond (tools/managed-backend? opts) [[:valkey/backend-finalize opts]]
          :else [])
    :else (mapv #(vector % opts) successors)))

(def side-effecting [:valkey/load-infrastructure :valkey/infrastructure :valkey/ssh-config
                    :valkey/ansible :valkey/acceptance :valkey/rehearsal :valkey/describe
                    :valkey/backend-finalize])
(def workflow
  (-> (wf/workflow {:start :valkey/start :wire-fn wire-fn :next-fn next-fn})
      progress/advise
      (dry-run/advise side-effecting)))
