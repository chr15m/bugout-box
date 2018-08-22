(ns bugout-box.core
    (:require
      [reagent.core :as r]
      [cljsjs.bugout :as Bugout]
      ["diceware-wordlist-en-eff" :as wordlist]))

(defonce state (r/atom {}))

(defonce bugout (Bugout. #js {:seed (aget js/localStorage "bugout-box-server-seed")}))
(aset js/localStorage "bugout-box-server-seed" (aget bugout "seed"))
(aset js/window "b" bugout)

(def nacl (.-nacl bugout))

;; -------------------------
;; Functions

(defn rand-nth-nacl [a]
  (let [r (/ (js/Uint16Array. (.slice (.-buffer (nacl.randomBytes 2)))) 65536)
        l (count a)]
    (get a (* l r))))

(defn make-passphrase []
  (let [dicey (vec (js/Object.keys wordlist))]
    (clojure.string/join " "
                         (map
                           #(aget wordlist (rand-nth-nacl dicey))
                           (range 6)))))

(defn now []
  (.getTime (js/Date.)))

(defn authenticate [pk]
  (when (not (contains? (set (@state :sudoers)) pk))
    #js {"error" (str pk " is not in sudoers.")}))

;; -------------------------
;; Mutation

(defn update-su-passphrase! []
  (swap! state assoc :su {:passphrase (make-passphrase) :t (now)}))

;; -------------------------
;; API

(.register bugout "ping"
           (fn [pk args cb]
             (cb #js {"pong" (now)})))

(.register bugout "su"
           (fn [pk args cb]
             ; TODO: also receive useragent & IP for user identification
             (if (= (aget args "passphrase") (-> @state :su :passphrase))
               (do
                 (swap! state update-in [:sudoers] conj pk)
                 (cb true))
               (cb false))))

(.register bugout "su-passphrase"
           (fn [pk args cb]
             ; TODO: generate an HMAC'ed one-time passphrase
             ; that lasts for a minute or two here
             (cb
               (or (authenticate pk)
                   (-> @state :su :passphrase)))))

(defn cron []
  ; check if su passphrase needs updating
  (when (> (now) (-> @state :su :t (+ 30000)))
    (update-su-passphrase!)
    (print "Updated su passphrase:" (-> @state :su :passphrase))))

;; -------------------------
;; Views

(defn home-page []
  [:div
   [:h2 "Bugout Box"]
   [:h4 (aget bugout "pk")]
   [:div#sudoers
    [:h3 "su passphrase"]
    [:p (-> @state :su :passphrase)]
    [:h3 "sudoers"]
    [:ul
     (for [s (-> @state :sudoers)]
       [:li {:key s} s])]]])

;; -------------------------
;; Initialize app

(defn mount-root []
  (r/render [home-page] (.getElementById js/document "app")))

(defn init! []
  (js/setInterval
    (fn [] (cron))
    1000)
  (mount-root))
