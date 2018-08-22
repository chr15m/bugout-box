(ns bugout-box.core
    (:require
      [reagent.core :as r]
      [cljsjs.bugout :as Bugout]))

(defonce bugout (Bugout. #js {:seed (aget js/localStorage "bugout-box-server-seed")}))
(aset js/localStorage "bugout-box-server-seed" (aget bugout "seed"))
(aset js/window "b" bugout)

;; -------------------------
;; API

(.register bugout "ping"
           (fn [pk args cb]
             (cb #js {"pong" (now)})))

(defn cron []
  (print "cron"))

;; -------------------------
;; Views

(defn home-page []
  [:div
   [:h2 "Bugout Box"]
   [:h4 (aget bugout "pk")]])

;; -------------------------
;; Initialize app

(defn mount-root []
  (r/render [home-page] (.getElementById js/document "app")))

(defn init! []
  (js/setInterval
    (fn [] (cron))
    1000)
  (mount-root))
