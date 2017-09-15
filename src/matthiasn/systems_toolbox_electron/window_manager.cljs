(ns matthiasn.systems-toolbox-electron.window-manager
  (:require [taoensso.timbre :as timbre :refer-macros [info debug warn]]
            [electron :refer [BrowserWindow]]
            [matthiasn.systems-toolbox.component :as stc]))

(defn new-window [{:keys [current-state cmp-state msg-payload]}]
  (let [{:keys [url width height window-id]} msg-payload
        window (BrowserWindow. (clj->js {:width  (or width 1200)
                                         :height (or height 800)
                                         :show   false}))
        window-id (or window-id (stc/make-uuid))
        show #(.show window)
        url (str "file://" (:app-path current-state) "/" url)
        new-state (-> current-state
                      (assoc-in [:main-window] window)
                      (assoc-in [:windows window-id] window)
                      (assoc-in [:active] window-id))
        new-state (if-let [loading (:loading new-state)]
                    (do (.close loading)
                        (dissoc new-state :loading))
                    new-state)
        focus (fn [_]
                (info "Focused" window-id)
                (swap! cmp-state assoc-in [:active] window-id))
        blur (fn [_]
               (info "Blurred" window-id)
               (swap! cmp-state assoc-in [:active] nil))
        close (fn [_]
                (info "Closed" window-id)
                (swap! cmp-state assoc-in [:active] nil)
                (swap! cmp-state update-in [:windows] dissoc window-id))
        ready (fn [_]
                (info "ready" window-id)
                (show)
                (.send (.-webContents window) "window-id" (str window-id)))]
    (info "Opening new window" url)
    (.on window "focus" #(js/setTimeout focus 10))
    (.once window "ready-to-show" ready)
    (.on window "blur" blur)
    (.on window "close" close)
    (.loadURL window url)
    {:new-state new-state}))

(defn active-window [current-state]
  (let [active (:active current-state)]
    (get-in current-state [:windows active])))

(defn relay-msg [{:keys [current-state msg-type msg-meta msg-payload]}]
  (let [window-id (:window-id msg-meta)
        active (:active current-state)
        window-ids (case window-id
                     :broadcast (keys (:windows current-state))
                     :active [active]
                     [window-id])]
    (doseq [window-id window-ids]
      (let [window (get-in current-state [:windows window-id])
            web-contents (when window (.-webContents window))
            serializable [msg-type {:msg-payload msg-payload :msg-meta msg-meta}]]
        (when web-contents
          (debug "Relaying" msg-type window-id)
          (.send web-contents "relay" (pr-str serializable))))))
  {})

(defn dev-tools [{:keys [current-state]}]
  (when-let [active-window (active-window current-state)]
    (info "Open dev-tools")
    (.openDevTools (.-webContents active-window)))
  {})

(defn close-window [{:keys [current-state msg-meta]}]
  (let [window-id (or (:window-id msg-meta)
                      (:active current-state))]
    (if-let [window (get-in current-state [:windows window-id])]
      (let [new-state (update-in current-state [:windows] dissoc window-id)]
        (info "Closing:" window-id window)
        (.close window)
        {:new-state new-state})
      (do (warn "WM: no such window" window-id)
          {}))))

(defn activate [{:keys [current-state]}]
  (info "Activate APP")
  (when (empty? (:windows current-state))
    {:send-to-self [:window/new {:url "view.html"}]}))

(defn cmp-map [cmp-id relay-types app-path]
  (let [relay-map (zipmap relay-types (repeat relay-msg))]
    {:cmp-id      cmp-id
     :state-fn    (fn [put-fn] {:state (atom {:app-path app-path})})
     :handler-map (merge relay-map
                         {:window/new       new-window
                          :window/activate  activate
                          :window/close     close-window
                          :window/dev-tools dev-tools})}))
