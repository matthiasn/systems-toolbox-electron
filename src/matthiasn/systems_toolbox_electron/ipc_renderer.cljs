(ns matthiasn.systems-toolbox-electron.ipc-renderer
  (:require [electron :refer [ipcRenderer]]
            [taoensso.timbre :as timbre :refer-macros [info debug error]]
            [goog.net.cookies]
            [cljs.reader :refer [read-string]]))

(defn state-fn [put-fn]
  (let [state (atom {})
        relay (fn [ev m]
                (try
                  (let [parsed (read-string m)
                        msg-type (first parsed)
                        {:keys [msg-payload msg-meta]} (second parsed)
                        msg (with-meta [msg-type msg-payload] msg-meta)]
                    (debug "IPC received" msg-type)
                    (put-fn msg))
                  (catch js/Object e (error e "when parsing" m))))
        id-handler (fn [ev window-id]
                     (info "IPC: window-id" window-id)
                     (.set goog.net.cookies "window-id" window-id))]
    (.on ipcRenderer "relay" relay)
    (.on ipcRenderer "window-id" id-handler)
    (info "Starting IPC Component")
    {:state state}))

(defn relay-msg [{:keys [current-state msg-type msg-meta msg-payload]}]
  (let [window-id (.get goog.net.cookies "window-id")
        msg-meta (assoc-in msg-meta [:window-id] window-id)
        serializable [msg-type {:msg-payload msg-payload
                                :msg-meta    msg-meta}]]
    (debug "Relay to MAIN:" (str msg-type) (str msg-payload))
    (.send ipcRenderer "relay" (pr-str serializable)))
  {})

(defn cmp-map [cmp-id relay-types]
  {:cmp-id      cmp-id
   :state-fn    state-fn
   :handler-map (zipmap relay-types (repeat relay-msg))})
