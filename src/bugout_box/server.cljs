(ns bugout-box.server
  (:require
    [reagent.core :as r]
    [alandipert.storage-atom :refer [local-storage]]
    [cljsjs.bugout :as Bugout]
    [goog.crypt :refer [hexToByteArray byteArrayToHex]]
    [goog.crypt.baseN :refer [recodeString BASE_LOWERCASE_HEXADECIMAL]]
    [goog.string :refer [padNumber]]
    [alphabase.core :refer [encode]]
    ["qrcodesvg/lib" :as qrcode])
  (:import [goog.crypt Sha512_256 Hmac Sha1]))

(defonce storage (local-storage (r/atom {}) "bugout-box-storage"))

(def re-magnet #"magnet:\?xt=urn:[a-z0-9A-Z]+:([a-z0-9A-Z]{32})")

;; -------------------------
;; Functions

(defn now []
  (.getTime (js/Date.)))

(defn authenticate [sudoers address]
  (when (not (get sudoers (keyword address)))
    {:error (str address " is not in sudoers.")}))

; reverse engineering of this:
; http://blog.tinisles.com/2011/10/google-authenticator-one-time-password-algorithm-in-javascript/
(defn totp [totp-key]
  "Compute TOTP numeral code string. totp-key must be a byte array."
  (let [epoch (-> (js/Date.) (.getTime) (/ 1000.0) (js/Math.round))
        t (-> epoch (/ 30) (js/Math.floor) )
        t (-> (str "0000000000000000" (.toString t 16)) (.slice -16) (hexToByteArray))
        hmac (let [hmac (Hmac. (Sha1.) totp-key)] (.getHmac hmac t))
        offset (mod (get (.slice hmac -1) 0) 16)
        b (.slice hmac offset (+ offset 4))
        otp (-> b (byteArrayToHex) (js/parseInt 16) (bit-and 0x7fffffff) (mod 1000000))
        otp (-> (str "000000" otp) (.slice -6))]
    otp))

(defn totp-secret-link [address totp-key]
  "Generate link for TOTP apps to import secret. totp-key must be a byte array."
  (str "otpauth://totp/" (.substring address 0 8) "@bugout-box?secret="
       (encode "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567" totp-key)))

(defn get-totp-key [seed]
  ; (hexToByteArray "48656c6c6f21deadbeef")
  (.slice (let [shasum (Sha512_256.)]
            (.update shasum (str "totp-seed-" seed "-totp-seed"))
            (.digest shasum))
          0 20))

; broadcast tab changes to all addresses
; broadcast api changes to all addresses

(defn broadcast [state message]
  (for [s (-> @state :shared :sudoers)]
    (.send (state :bugout) s (clj->js message))))

(defn send-back [cb result]
  (cb (clj->js result)))

(defn shared-state [state]
  (-> state :shared))

(defn sync-tabs! [state]
  "Ensure the desired tabs are open."
  (let [tabs (-> @state :shared :tabs)
        tabrefs (-> @state :tabrefs)
        unopened-tabs (clojure.set/difference (set (keys tabs)) (set (keys tabrefs)))
        unclosed-tabs (clojure.set/difference (set (keys tabrefs)) (set (keys tabs)))]
    (print "tab changes" unopened-tabs unclosed-tabs)
    (print "tabs to open:" (map #(aget (tabs %) "url") unopened-tabs))
    (print "tabs to close:" unclosed-tabs)
    ; open all unopened tabs
    (doseq [t unopened-tabs]
      (let [url (aget (tabs t) "url")
            w (.open js/window url t)]
        (swap! state assoc-in [:tabrefs t] w)))
    ; close all closed tabs
    (doseq [t unclosed-tabs]
      (when (tabrefs t)
        (.close (tabrefs t)))
      (swap! state update-in [:tabrefs] dissoc t))))

(defn sync-torrents! [state]
  "Ensure the desired torrents are seeded."
  (let [torrents (-> @state :shared :torrents)
        torrents-wt (.. (@state :bugout) -wt -torrents)
        wanted-keys (set (keys torrents))
        added-keys (set (remove nil? (map #(when (.-bugoutseeded %) (.-infoHash %)) torrents-wt)))
        unadded-torrents (clojure.set/difference wanted-keys added-keys)
        unremoved-torrents (clojure.set/difference added-keys wanted-keys)]
    (print "sync-torrents!")
    (print "changes:" unadded-torrents unremoved-torrents)
    (doseq [i unadded-torrents]
      (let [magnet-link (get torrents i)
            torrent (.add (.-wt (@state :bugout)) magnet-link)]
        (aset torrent "bugoutseeded" true)))
    (doseq [i unremoved-torrents]
      (.remove (.-wt (@state :bugout)) i))))

; run checks every second
(defn cron [])

;; -------------------------
;; UI functions

(defn hide! [a & [ev]]
  (reset! a nil)
  (when ev
    (.preventDefault ev)))

(defn reveal! [a & ms [ev]]
  (reset! a true)
  (when ev
    (.preventDefault ev))
  (js/setTimeout
    #(hide! a)
    (or ms (* 1000 30))))

;; -------------------------
;; Views

(defn component-server [state]
  (let [bugout (@state :bugout)
        show-pw (r/atom nil)
        show-key (r/atom nil)
        totp-key (get-totp-key (aget bugout "seed"))
        address (.address bugout)]
    (fn []
      [:div
       [:h2 "Bugout Box"]
       [:h4 (.address bugout)]
       [:p (@state :connections) " connections"]
       [:div
        [:h3 "authentication"]
        [:ul
         [:ol (if @show-pw
                [:span (totp totp-key) " " [:a {:on-click (partial hide! show-pw)} "hide"]]
                [:a {:on-click (partial reveal! show-pw (* 10 1000))} "Reveal one-time-password"])]
         [:ol (if @show-key
                [:div
                 [:div {:dangerouslySetInnerHTML {:__html (.generate (qrcode. (totp-secret-link address totp-key) 400))}}]
                 [:pre (totp-secret-link address totp-key)]]
                [:a {:on-click (partial reveal! show-key (* 10 1000))} "Reveal TOTP QR code"])]]
        [:h3 "state"]
        [:pre
         (pr-str @state)]]])))

(defn init-server [state]
  (defonce cron-loop
    (js/setInterval
      (fn [] (cron))
      1000))

  (swap! state (fn [old-state]
                 (if (old-state :bugout)
                   old-state
                   (let [bugout (Bugout. #js {:seed (aget js/localStorage "bugout-box-server-seed")})]
                     (aset js/localStorage "bugout-box-server-seed" (aget bugout "seed"))
                     (aset js/window "b" bugout)
                     (.on bugout "rpc" (partial js/console.log "rpc:"))
                     (.on bugout "connections" (partial js/console.log "connections:"))
                     (.on bugout "connections" #(swap! state assoc :connections %))
                     (assoc old-state :bugout bugout)))))
  
  (let [bugout (@state :bugout)]
    (js/console.log "bugout-address" (.replace js/document.location.href "server.html" (str "#" (.address bugout))))

    (let [totp-key (get-totp-key (aget bugout "seed"))]

      (.register bugout "ping"
                 (fn [address args cb]
                   (send-back cb {:pong (now)})))

      (.register bugout "su"
                 (fn [address args cb]
                   (send-back cb
                              (if (= (aget args "totp") (totp totp-key))
                                (do
                                  (swap! state update-in [:shared :sudoers] assoc (keyword address) {:client (aget args "client") :agent (aget args "agent")})
                                  (shared-state @state))
                                {:error "TOTP authentication failed."}))))

      (.register bugout "su-remove"
                 (fn [address args cb]
                   (send-back cb (or (authenticate (-> @state :shared :sudoers) address)
                                     (do 
                                       (swap! state update-in [:shared :sudoers] dissoc (keyword args))
                                       (shared-state @state))))))

      (.register bugout "tab-open"
                 (fn [address args cb]
                   (send-back cb (or (authenticate (-> @state :shared :sudoers) address)
                                     (do
                                       (swap! state update-in [:shared :tabs] assoc (byteArrayToHex (.randomBytes (.-nacl (@state :bugout)) 4)) args)
                                       (sync-tabs! state)
                                       (shared-state @state))))))

      (.register bugout "tab-close"
                 (fn [address args cb]
                   (send-back cb (or (authenticate (-> @state :shared :sudoers) address)
                                     (do
                                       (swap! state update-in [:shared :tabs] dissoc (aget args "id"))
                                       (sync-tabs! state)
                                       (shared-state @state))))))

      (.register bugout "torrent-add"
                 (fn [address args cb]
                   (send-back cb (or (authenticate (-> @state :shared :sudoers) address)
                                     (let [[uri infohash] (re-find re-magnet (aget args "magnet"))]
                                       (when infohash
                                         (swap! state update-in [:shared :torrents] assoc infohash (aget args "magnet")))
                                       (sync-torrents! state)
                                       (shared-state @state))))))

      (.register bugout "torrent-remove"
                 (fn [address args cb]
                   (send-back cb (or (authenticate (-> @state :shared :sudoers) address)
                                     (do
                                       (swap! state update-in [:shared :torrents] dissoc (aget args "infohash"))
                                       (sync-torrents! state)
                                       (shared-state @state))))))

      (.register bugout "get-state"
                 (fn [address args cb]
                   (print "get state call")
                   (send-back cb (or (authenticate (-> @state :shared :sudoers) address)
                                     (shared-state @state)))))

      (.register bugout "storage-set"
                 (fn [address args cb]
                   (send-back cb (or (authenticate (-> @state :shared :sudoers) address)
                                     (swap! storage assoc-in (vec (aget args "key")) (aget args "value"))))))

      (.register bugout "storage-remove"
                 (fn [address args cb]
                   (send-back cb (or (authenticate (-> @state :shared :sudoers) address)
                                     (let [k (vec (aget args "key"))]
                                       (if (> (count k) 1)
                                         (swap! storage update-in (butlast k) dissoc (last k))
                                         (swap! storage dissoc (first k))))))))

      (.register bugout "storage-get"
                 (fn [address args cb]
                   (send-back cb (or (authenticate (-> @state :shared :sudoers) address)
                                     (get-in @storage (vec (aget args "key")))))))

      ; if some key is set to some value
      ; then set this other key to this other value
      (.register bugout "storage-compare-and-set"
                 (fn [address args cb]
                   (send-back cb (or (authenticate (-> @state :shared :sudoers) address)
                                     (swap! storage
                                            (fn [storage-previous]
                                              (if (= (get-in storage-previous (vec (aget args "key-cas"))) (aget args "value-cas"))
                                                (assoc-in storage-previous (vec (aget args "key")) (aget args "value"))
                                                storage-previous)))))))

      (.register bugout "storage-keys"
                 (fn [address args cb]
                   (send-back cb (or (authenticate (-> @state :shared :sudoers) address)
                                     (keys @storage))))))))

;; -------------------------
;; API

; (.register bugout "api-set" (fn []))

; (.register bugout "api-keys" (fn []))

; (.register bugout "api-remove-call")

; (.register bugout "inbox-store")

; (.register bugout "inbox-read")

; (.register fetch-local "")

; (.register execute "") ; run local shell command


