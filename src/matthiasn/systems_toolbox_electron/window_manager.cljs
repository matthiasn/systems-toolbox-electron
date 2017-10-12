(ns matthiasn.systems-toolbox-electron.window-manager
  (:require [taoensso.timbre :as timbre :refer-macros [info debug warn]]
            [electron :refer [BrowserWindow]]
            [matthiasn.systems-toolbox.component :as stc]))

(defn serialize [msg-type msg-payload msg-meta]
  (let [serializable [msg-type {:msg-payload msg-payload :msg-meta msg-meta}]]
    (pr-str serializable)))

(defn new-window [{:keys [current-state cmp-state msg-payload]}]
  (let [{:keys [url width height window-id cached]} msg-payload]
    (if (get-in current-state [:windows window-id])
      (do (info "WM: window id exists, not creating new one:" window-id)
          {})
      (let [load-new (fn [url]
                       (let [window (BrowserWindow.
                                      (clj->js {:width  (or width 1200)
                                                :height (or height 800)
                                                :show   false}))]
                         (info "WM load-new" url)
                         (.loadURL window url)
                         window))
            spare (when cached (:spare current-state))
            url (str "file://" (:app-path current-state) "/" url)
            new-spare (when cached (load-new url))
            new-spare-wc (when cached (.-webContents new-spare))
            new-spare-init #(let [js "window.location = ''"
                                  s (serialize :exec/js {:js js} {})]
                              (.send new-spare-wc "relay" s))
            window-id (or window-id (stc/make-uuid))
            window (or spare (load-new url))
            show #(.show window)
            new-state (-> current-state
                          (assoc-in [:main-window] window)
                          (assoc-in [:spare] new-spare)
                          (assoc-in [:windows window-id] window)
                          (assoc-in [:active] window-id))
            new-state (if-let [loading (:loading new-state)]
                        (do (.close loading)
                            (dissoc new-state :loading))
                        new-state)
            focus (fn [_]
                    (debug "Focused" window-id)
                    (swap! cmp-state assoc-in [:active] window-id))
            blur (fn [_]
                   (debug "Blurred" window-id)
                   ;(swap! cmp-state assoc-in [:active] nil)
                   )
            close (fn [_]
                    (debug "Closed" window-id)
                    (swap! cmp-state assoc-in [:active] nil)
                    (swap! cmp-state update-in [:windows] dissoc window-id))
            send-id #(.send (.-webContents window) "window-id" (str window-id))
            ready (fn [_]
                    (debug "ready" window-id)
                    (show)
                    (dotimes [n 10] (js/setTimeout send-id (* n 1000))))]
        (info "Opening new window" url window-id)
        (.on window "focus" #(js/setTimeout focus 10))
        (when cached (.on new-spare-wc "did-finish-load" new-spare-init))
        (if spare
          (do (info "WM using spare" spare)
              (show)
              (.send (.-webContents window) "window-id" (str window-id)))
          (.on (.-webContents window) "did-finish-load" #(js/setTimeout show 10)))
        (.once window "ready-to-show" ready)
        (.on window "blur" blur)
        (.on window "close" close)
        {:new-state new-state}))))

(defn active-window [current-state]
  (let [active (:active current-state)]
    (get-in current-state [:windows active])))

(defn relay-msg [{:keys [current-state msg-type msg-meta msg-payload]}]
  (let [window-id (or (:window-id msg-meta) :active)
        active (:active current-state)
        window-ids (case window-id
                     :broadcast (keys (:windows current-state))
                     :active [active]
                     [window-id])]
    (doseq [window-id window-ids]
      (let [window (get-in current-state [:windows window-id])
            web-contents (when window (.-webContents window))
            serialized (serialize msg-type msg-payload msg-meta)]
        (when web-contents
          (debug "Relaying" msg-type window-id)
          (.send web-contents "relay" serialized)))))
  {})

(defn dev-tools [{:keys [current-state]}]
  (when-let [active-window (active-window current-state)]
    (info "Open dev-tools")
    (.openDevTools (.-webContents active-window)))
  {})

(defn close-window [{:keys [current-state msg-meta]}]
  (let [window-id (:window-id msg-meta)
        window-id (if (= :active window-id)
                    (:active current-state)
                    window-id)]
    (if-let [window (get-in current-state [:windows window-id])]
      (let [new-state (update-in current-state [:windows] dissoc window-id)]
        (info "Closing:" window-id window)
        (.close window)
        {:new-state new-state})
      (do (warn "WM: no such window" window-id)
          {}))))

(defn minimize-window [{:keys [current-state msg-meta msg-payload]}]
  (let [window-id (or (:window-id msg-meta) :active)
        active (:active current-state)
        window-ids (case window-id
                     :broadcast (keys (:windows current-state))
                     :active [active]
                     [window-id])]
    (doseq [window-id window-ids]
      (let [window (get-in current-state [:windows window-id])]
        (.minimize window))))
  {})

(defn restore-window [{:keys [current-state msg-meta msg-payload]}]
  (let [window-id (or (:window-id msg-meta) :active)
        active (:active current-state)
        window-ids (case window-id
                     :broadcast (keys (:windows current-state))
                     :active [active]
                     [window-id])]
    (doseq [window-id window-ids]
      (let [window (get-in current-state [:windows window-id])]
        (.restore window))))
  {})

(defn hide-window [{:keys [current-state msg-meta msg-payload]}]
  (let [window-id (or (:window-id msg-meta) :active)
        active (:active current-state)
        window-ids (case window-id
                     :broadcast (keys (:windows current-state))
                     :active [active]
                     [window-id])]
    (doseq [window-id window-ids]
      (let [window (get-in current-state [:windows window-id])]
        (.hide window))))
  {})

(defn show-window [{:keys [current-state msg-meta msg-payload]}]
  (let [window-id (or (:window-id msg-meta) :active)
        active (:active current-state)
        window-ids (case window-id
                     :broadcast (keys (:windows current-state))
                     :active [active]
                     [window-id])]
    (doseq [window-id window-ids]
      (let [window (get-in current-state [:windows window-id])]
        (.show window))))
  {})

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
                          :window/minimize  minimize-window
                          :window/restore   restore-window
                          :window/hide      hide-window
                          :window/show      show-window
                          :window/dev-tools dev-tools})}))
