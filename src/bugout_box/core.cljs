(ns bugout-box.core
  (:require
    [reagent.core :as r]
    [bugout-box.server :refer [init-server component-server]]
    [bugout-box.client :refer [init-client component-client]]))

; TODO;
; * open/close tabs 
; * webtorrent file seeding
;
; * inbox
; * .js based server specification
; * server.html bugout template
; * storage get/set
; * register API calls
; * shell access interface
; * gpio access on rpi

(defonce state (r/atom {}))

(def is-server? (not= (.indexOf (.. js/document -location -href) "server.html") -1))

(defn mount-root []
  (if is-server?
    (init-server state)
    (init-client state))
  (r/render [(if is-server? component-server component-client) state] (.getElementById js/document "app")))

(defn init! []
  (mount-root))
