(ns bugout-box.server
  (:require
    [reagent.core :as r]
    [goog.crypt :refer [hexToByteArray byteArrayToHex]]
    [goog.crypt.baseN :refer [recodeString BASE_LOWERCASE_HEXADECIMAL]]
    [goog.string :refer [padNumber]]
    [alphabase.core :refer [encode]]
    ["qrcodesvg/lib" :as qrcode])
  (:import [goog.crypt Sha512_256 Hmac Sha1]))

(defonce storage (r/atom {}))

(defn get-totp-key [seed]
  ; (hexToByteArray "48656c6c6f21deadbeef")
  (.slice (let [shasum (Sha512_256.)]
            (.update shasum (str "totp-seed-" seed "-totp-seed"))
            (.digest shasum))
          0 20))

;; -------------------------
;; Functions

(defn now []
  (.getTime (js/Date.)))

(defn authenticate [sudoers address]
  (when (not (contains? (set sudoers) address))
    #js {"error" (str address " is not in sudoers.")}))

; reverse engineering of this:
; http://blog.tinisles.com/2011/10/google-authenticator-one-time-password-algorithm-in-javascript/
(defn totp [totp-key]
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
  (str "otpauth://totp/" (.substring address 0 8) "@bugout-box?secret="
       (encode "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567" totp-key)))

;; -------------------------
;; API

; (.register bugout "su-remove")

;(.register bugout "tabs-list")

;(.register bugout "tabs-open")

;(.register bugout "tabs-close")

;(.register bugout "seed-infohash")

; (.register bugout "api-set" (fn []))

; (.register bugout "api-keys" (fn []))

; (.register bugout "api-remove-call")

; (.register bugout "storage-set" (fn []))

; (.register bugout "storage-get" (fn []))

; (.register bugout "storage-keys" (fn []))

; (.register bugout "storage-compare-and-set")

; (.register bugout "storage-remove" (fn []))

; (.register bugout "inbox-store")

; (.register bugout "inbox-read")

; (.register fetch-local "")

; (.register execute "") ; run local shell command

; broadcast tab changes to all addresses
; broadcast api changes to all addresses

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

(defn component-server [bugout state]
  (let [show-pw (r/atom nil)
        show-key (r/atom nil)
        totp-key (get-totp-key (aget bugout "seed"))
        address (.address bugout)]
    (fn []
      [:div
       [:h2 "Bugout Box"]
       [:h4 (.address bugout)]
       [:div#sudoers
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

(defn init-server [bugout state]
  (defonce cron-loop
    (js/setInterval
      (fn [] (cron))
      1000))
  
  (let [totp-key (get-totp-key (aget bugout "seed"))]
    (.register bugout "ping"
               (fn [address args cb]
                 (cb #js {"pong" (now)})))

    (.register bugout "su"
               (fn [address args cb]
                 ; TODO: also receive useragent & IP
                 ; to help user identify their devices
                 (if (= (aget args "otp") (totp totp-key))
                   (do
                     (swap! state update-in [:sudoers] #(-> % (conj address) set vec))
                     (cb true))
                   (cb false))))))

