(ns bugout-box.client
  (:require
    [reagent.core :as r]
    [cljsjs.bugout :as Bugout]))

(defn connect! [state address]
  (let [bugout (Bugout. address #js {:seed (aget js/localStorage "bugout-box-client-seed")})]
    (swap! state assoc
           :address address
           :bugout bugout)
    (aset js/localStorage "bugout-box-server-seed" (aget bugout "seed"))
    (aset js/window "b" bugout)
    (js/console.log "bugout-address" (str js/document.location.href "#" (.address bugout)))
    (.on bugout "connection"
         (fn [c]
           (swap! state assoc :connections c)))))

(defn component-enter-address [state]
  (let [address (r/atom "")]
    (fn []
      [:div
       [:input {:placeholder "Bugout server address"
                :value @address
                :on-change #(reset! address (.. % -target -value))}]
       [:button {:on-click (partial connect! state @address)} "Connect"]])))

(defn component-connecting [state])

(defn component-main-interface [state]
  (if (@state :bugout)
              [:div
               [:div "Connected to" (.-identifier (@state :bugout))]
               [:div (@state :connections) "connections"]]))

(defn component-client [state]
  (cond
    (nil? (@state :address)) [component-enter-address state]
    (<= (@state :connections) 0) [component-connecting state]
    :else [component-main-interface state]))

(defn init-client [state]
  (let [address (.. js/document -location -hash)]
    (when (not= address "")
      (connect! state address))))
