(ns bugout-box.core
  (:require
    [reagent.core :as r]
    [cljsjs.bugout :as Bugout]
    [bugout-box.server :refer [init-server component-server]]))

; TODO;
; * open/close .js based servers
;
; * open/close tabs 
; * storage get/set
; * register API calls
; * inbox
; * shell access interface
; * gpio access on rpi

(defonce state (r/atom {}))

(defonce bugout (Bugout. #js {:seed (aget js/localStorage "bugout-box-server-seed")}))
(aset js/localStorage "bugout-box-server-seed" (aget bugout "seed"))
(aset js/window "b" bugout)

(defn component-client []
  [:div "Hello"])

(def is-server? (not= (.indexOf (.. js/document -location -href) "server.html") -1))

(defn mount-root []
  (if is-server?
    (init-server bugout state))
  (r/render [(if is-server? component-server component-client) bugout state] (.getElementById js/document "app")))

(defn init! []
  (js/console.log "bugout-address" (str js/document.location.href "#" (.address bugout)))
  (mount-root))
