(ns bugout-box.core
    (:require
      [reagent.core :as r]
      [cljsjs.bugout :as Bugout]
      [goog.crypt :refer [hexToByteArray byteArrayToHex]]
      [goog.crypt.baseN :refer [recodeString BASE_LOWERCASE_HEXADECIMAL]]
      [goog.string :refer [padNumber]]
      [alphabase.core :refer [encode]]
      ["qrcodesvg/lib" :as qrcode])
  (:import [goog.crypt Sha512_256 Hmac Sha1]))

; TODO;
; * generate one-time passphrase when user requests only
; * register API calls
; * open/close tabs
; * inbox
; * shell access interface
; * gpio access on rpi

(defonce state (r/atom {}))
(defonce storage (r/atom {}))

(defonce bugout (Bugout. #js {:seed (aget js/localStorage "bugout-box-server-seed")}))
(aset js/localStorage "bugout-box-server-seed" (aget bugout "seed"))
(aset js/window "b" bugout)

(def totp-key (.slice (let [shasum (Sha512_256.)] (.update shasum (str "totp-seed-" (aget bugout "seed") "-totp-seed")) (.digest shasum)) 0 20))
;(def totp-key (hexToByteArray "48656c6c6f21deadbeef"))

(def nacl (.-nacl bugout))

;; -------------------------
;; Functions

(defn now []
  (.getTime (js/Date.)))

(defn authenticate [address]
  (when (not (contains? (set (@state :sudoers)) address))
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

(defn totp-secret-link [totp-key]
  (str "otpauth://totp/" (.substring (.address bugout) 0 8) "@bugout-box?secret="
       (encode "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567" totp-key)))

;; -------------------------
;; API

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
               (cb false))))

; (.register bugout "su-remove")

;(.register bugout "tabs-list")

;(.register bugout "tabs-open")

;(.register bugout "tabs-close")

;(.register bugout "seed-infohash")

(.register bugout "api-set" (fn []))

(.register bugout "api-keys" (fn []))

; (.register bugout "api-remove-call")

(.register bugout "storage-set" (fn []))

(.register bugout "storage-get" (fn []))

(.register bugout "storage-keys" (fn []))

; (.register bugout "storage-compare-and-set")

(.register bugout "storage-remove" (fn []))

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

(defn home-page []
  (let [show-pw (r/atom nil)
        show-key (r/atom nil)]
    (fn []
      [:div
       [:h2 "Bugout Box"]
       [:h4 (.address bugout)]
       [:div#sudoers
        [:h3 "authentication"]
        [:ul
         [:ol (if @show-pw
                [:span (totp totp-key) " " [:a {:on-click (partial hide! show-pw)} "X"]]
                [:a {:on-click (partial reveal! show-pw (* 10 1000))} "Reveal one-time-password"])]
         [:ol (if @show-key
                [:div
                 [:div {:dangerouslySetInnerHTML {:__html (.generate (qrcode. (totp-secret-link totp-key) 400))}}]
                 [:pre (totp-secret-link totp-key)]]
                [:a {:on-click (partial reveal! show-key (* 10 1000))} "Reveal TOTP QR code"])]]
        [:h3 "sudoers"]
        [:ul
         (for [s (-> @state :sudoers)]
           [:li {:key s} s])]]])))

;; -------------------------
;; Initialize app

(defn mount-root []
  (r/render [home-page] (.getElementById js/document "app")))

(defn init! []
  (js/setInterval
    (fn [] (cron))
    1000)
  (mount-root))
