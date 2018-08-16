(ns ^:figwheel-no-load bugout-box.dev
  (:require
    [bugout-box.core :as core]
    [devtools.core :as devtools]))


(enable-console-print!)

(devtools/install!)

(core/init!)
