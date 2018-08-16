(ns bugout-box.prod
  (:require
    [bugout-box.core :as core]))

;;ignore println statements in prod
(set! *print-fn* (fn [& _]))

(core/init!)
