(ns io.github.getcolors.valkey.validate
  (:require [clojure.string :as str]
            [green.cli :as green-cli]
            [io.github.getcolors.compute-ssh :as ssh]
            [io.github.getcolors.compute :as render]))

(def profile-par (green-cli/par-name :profile))

(def default-compute-provider "vultr")

(def required
  [:profile :workdir :provider-compute :provider-backend
   :compute-prevent-destroy
   :valkey-image :valkey-version :valkey-port
   :valkey-backup-r2-bucket :valkey-backup-r2-endpoint :valkey-backup-r2-region
   :valkey-backup-oncalendar :valkey-backup-retention-days
   :valkey-backup-max-age-hours])

;; `tag@sha256:...` pins both the human-readable release and the exact bytes.
;; Tags can be rebuilt; the digest identifies the tested bytes.
(def image-re #"^[a-z0-9]+(?:[._-][a-z0-9]+)*(?::[0-9]+)?(?:/[a-z0-9]+(?:[._-][a-z0-9]+)*)*(?::[A-Za-z0-9_][A-Za-z0-9_.-]*)?@sha256:[0-9a-f]{64}$")
(def url-re #"^https://[a-zA-Z0-9](?:[a-zA-Z0-9.-]*[a-zA-Z0-9])?(?::[0-9]{1,5})?$")
(def bucket-re #"^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$")
(def region-re #"^[a-z0-9]+(?:-[a-z0-9]+)*$")
(def calendar-re #"^[A-Za-z0-9_*:,./+ -]+$")

(defn missing? [x] (or (nil? x) (and (string? x) (str/blank? x))))

(defn keygen? [opts] (= "managed" (:mode (ssh/mode opts))))

(defn env-errors [env]
  (when (not-empty (str (get env profile-par)))
    [(str profile-par " is set; profile must come from colors.yml only")]))

(defn- positive-int? [v] (and (integer? v) (pos? v)))

(defn state-errors
  "Application settings and the library backend contract."
  [opts]
  (vec
   (concat
    (for [k required
          :when (missing? (get opts k))]
      (str k " is required"))
    (when-not (contains? #{"s3" "r2"} (:provider-backend opts))
      [":provider-backend must be s3 or r2"])
    (when-not (boolean? (:compute-prevent-destroy opts))
      [":compute-prevent-destroy must be true or false"])
    (let [v (:valkey-image opts)]
      (when (and (not (missing? v)) (not (re-matches image-re (str v))))
        [":valkey-image must be an OCI image reference with an immutable digest"]))
    (let [v (:valkey-image opts)]
      (when (and (not (missing? v)) (not (str/includes? (str v) "@sha256:")))
        [":valkey-image must be pinned by digest (tag@sha256:...)"]))
    (let [v (:valkey-version opts)]
      (when (and (not (missing? v)) (not (re-matches #"[0-9]+\.[0-9]+\.[0-9]+" (str v))))
        [":valkey-version must be an exact numeric release (major.minor.patch)"]))
    (let [v (:valkey-port opts)]
      (when (and (not (missing? v)) (not (and (integer? v) (<= 1 v 65535))))
        [":valkey-port must be an integer between 1 and 65535"]))
    (when-not (or (missing? (:valkey-backup-r2-endpoint opts))
                  (re-matches url-re (str (:valkey-backup-r2-endpoint opts))))
      [":valkey-backup-r2-endpoint must be an https URL"])
    (let [bucket (:valkey-backup-r2-bucket opts)]
      (when (and (not (missing? bucket))
                 (or (not (re-matches bucket-re (str bucket)))
                     (re-find #"\.\.|\.-|-\." (str bucket))))
        [":valkey-backup-r2-bucket must be a DNS bucket name (3-63 lowercase letters, digits, dots or hyphens)"]))
    (let [region (:valkey-backup-r2-region opts)]
      (when (and (not (missing? region)) (not (re-matches region-re (str region))))
        [":valkey-backup-r2-region must contain lowercase letters, digits and hyphens only"]))
    (let [calendar (:valkey-backup-oncalendar opts)]
      (when (and (not (missing? calendar)) (not (re-matches calendar-re (str calendar))))
        [":valkey-backup-oncalendar must be one plain systemd calendar expression without quotes or controls"]))
    (for [k [:valkey-backup-retention-days :valkey-backup-max-age-hours]
          :let [v (get opts k)]
          :when (and (not (missing? v)) (not (positive-int? v)))]
      (str k " must be a positive integer"))
    (try (render/backend-plan opts (str (:profile opts) "/shared.tfstate")) []
         (catch Exception e [(ex-message e)])))))

(def application-secrets
  "What converging the machine needs, and therefore only a create: the R2
  pair the backup sets are written with. The Valkey password is deliberately
  absent — it is generated on the server, once, and never operator-supplied."
  [:valkey-backup-r2-access-key-id :valkey-backup-r2-secret-access-key])

(defn secret-errors [opts event]
  (for [key (when (= :create event) application-secrets)
        :when (missing? (get opts key))]
    (str "required credential is not set: " (green-cli/par-name key))))
