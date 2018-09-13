(ns bugout-box.client
  (:require
    [reagent.core :as r]
    [cljsjs.bugout :as Bugout]))

(defn is-authenticated? [state]
  (get (-> state :remote :sudoers) (keyword (.address (state :bugout)))))

(defn update-remote! [state new-remote-state]
  (js/console.log "got updated state" new-remote-state)
  (swap! state assoc :remote (js->clj new-remote-state :keywordize-keys true)))

(defn refresh-api! [state]
  (let [bugout (@state :bugout)]
    (when bugout)))

(defn request-state! [state]
  (print "requesting state")
  (.rpc (@state :bugout) "get-state" nil (partial update-remote! state)))

(defn on-connections [state c]
  (let [old-c (@state :connections)]
    (swap! state (fn [old-state]
                   (let [new-state old-state
                         ;new-state (if (= c 0) (dissoc new-state :remote) new-state)
                         new-state (assoc new-state :connections c)]
                     new-state)))
    ; handle re-connect
    (when (and (= c 1) (= old-c 0) (.-serveraddress (@state :bugout)))
      (request-state! state))))

(defn connect! [state address]
  (let [bugout (Bugout. address #js {:seed (aget js/localStorage "bugout-box-client-seed")})]
    (swap! state assoc
           :address address
           :bugout bugout)
    (refresh-api! state)
    (aset js/localStorage "bugout-box-server-seed" (aget bugout "seed"))
    (aset js/window "b" bugout)
    (js/console.log "bugout address" (.address bugout))
    (js/console.log "bugout identifier" (.-identifier bugout))
    (.on bugout "connections" (partial js/console.log "connections:"))
    (.on bugout "rpc-response" (partial js/console.log "rpc-response:"))
    ; fire off request for current state
    (.on bugout "server"
         (fn [address]
           (print "Seen server:" address)
           (js/console.log "server address" (.-serveraddress bugout))
           (request-state! state)))
    
    (.on bugout "connections" (partial #'on-connections state))))

(defn authenticate! [state totp]
  (swap! state dissoc :remote)
  (.rpc (@state :bugout) "su"
        #js {:totp totp
             :client js/document.location.href
             :agent js/navigator.userAgent}
        (partial update-remote! state)))

(defn component-enter-address [state]
  (let [address (r/atom "")]
    (fn []
      [:div
       [:input {:placeholder "Bugout server address"
                :value @address
                :on-change #(reset! address (.. % -target -value))}]
       [:button {:on-click (partial connect! state @address)} "Connect"]])))

(defn component-connecting [state]
  [:div "Connecting..."])

(defn component-main-interface [state]
  [:div
   [:h3 (.address (@state :bugout))]
   [:div "Connected to " (.-identifier (@state :bugout))]
   [:div (@state :connections) " connections"]
   [:h2 "sudoers"]
   [:ul
    (for [[s details] (-> @state :remote :sudoers)]
      [:li {:key s} s
       [:div (details :client)]
       [:div (details :agent)]])]])

(defn component-request [state]
  [:div "Waiting for server..."])

(defn component-authenticate [state]
  (let [totp (r/atom "")]
    (fn []
      [:div
       [:div.error (-> @state :remote :error)]
       [:input {:placeholder "One-time password"
                :value @totp
                :type "number"
                :min 1
                :max 999999
                :max-length 6
                :on-change #(reset! totp (.slice (.. % -target -value) 0 6))}]
       [:button {:on-click (partial authenticate! state @totp)} "Authenticate"]])))

(defn component-client [state]
  (cond
    (nil? (@state :address)) [component-enter-address state]
    (<= (@state :connections) 0) [component-connecting state]
    (not (@state :remote)) [component-request state]
    (not (is-authenticated? @state)) [component-authenticate state]
    :else [component-main-interface state]))

(defn init-client [state]
  (let [address (.. js/document -location -hash)]
    (when (and (not= address "") (not (@state :bugout)))
      (connect! state (.substr address 1))))
  (refresh-api! state))
