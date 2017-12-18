(ns matthiasn.systems-toolbox-electron.ipc-main
  (:require [taoensso.timbre :refer-macros [info debug]]
            [electron :refer [ipcMain]]
            [cognitect.transit :as t]))

(defn state-fn [put-fn]
  (let [state (atom {})
        r (t/reader :json)
        relay (fn [ev m]
                (let [parsed (t/read r m)
                      msg-type (first parsed)
                      {:keys [msg-payload msg-meta]} (second parsed)
                      msg (with-meta [msg-type msg-payload] msg-meta)]
                  (debug "IPC relay:" msg-type)
                  (put-fn msg)))]
    (.on ipcMain "relay" relay)
    {:state state}))

(defn cmp-map [cmp-id]
  {:cmp-id   cmp-id
   :state-fn state-fn})
