(ns matthiasn.systems-toolbox-electron.window-manager
  (:require [taoensso.timbre :refer-macros [info debug warn]]
            [electron :refer [app BrowserWindow]]
            [cognitect.transit :as t]
            [matthiasn.systems-toolbox.component :as stc]
            [clojure.string :as s]))

(def w (t/writer :json))

(defn serialize [msg-type msg-payload msg-meta]
  (let [serializable [msg-type {:msg-payload msg-payload :msg-meta msg-meta}]]
    (t/write w serializable)))

(defn new-window [{:keys [current-state cmp-state msg-payload put-fn]}]
  (let [{:keys [url width height x y window-id cached opts]} msg-payload]
    (if (get-in current-state [:windows window-id])
      (do (info "WM: window id exists, not creating new one:" window-id) {})
      (let [default-opts {:width          (or width 1280)
                          :height         (or height 800)
                          :show           false
                          :webPreferences {:nodeIntegration true}}
            window-opts (merge default-opts (when (and x y) {:x x :y y}) opts)
            load-new (fn [url]
                       (let [window (BrowserWindow. (clj->js window-opts))]
                         (info "WM load-new" url window-opts)
                         (.loadURL window url)
                         window))
            spare (when cached (:spare current-state))
            url (if (s/includes? url "localhost")
                  url
                  (str "file://" (:app-path current-state) "/" url))
            new-spare (when cached (load-new url))
            new-spare-wc (when cached (.-webContents new-spare))
            new-spare-init #(let [js "window.location = ''"
                                  s (serialize :exec/js {:js js} {})]
                              (.send new-spare-wc "relay" s))
            window-id (or window-id (str (stc/make-uuid)))
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
            blur (fn [_] (debug "Blurred" window-id))
            close (fn [_]
                    (debug "Closed" window-id)
                    (swap! cmp-state assoc-in [:active] nil)
                    (swap! cmp-state update-in [:windows] dissoc window-id))
            send-id #(let [window (get-in @cmp-state [:windows window-id])]
                       (when window
                         (.send (.-webContents window) "window-id" (str window-id))))
            ready (fn [_]
                    (debug "ready" window-id)
                    (show)
                    (dotimes [n 10] (js/setTimeout send-id (* n 1000))))
            web-contents (.-webContents window)
            handle-redirect (fn [ev url]
                              (.preventDefault ev)
                              (put-fn [:wm/open-external url]))
            resized-moved (fn [_]
                            (let [bounds (js->clj (.getContentBounds window)
                                                  :keywordize-keys true)]
                              (put-fn [:window/resized bounds])
                              (debug "resize" bounds)))]
        (.on window "focus" #(js/setTimeout focus 10))
        (.on window "resize" resized-moved)
        (.on window "move" resized-moved)
        (when cached (.on new-spare-wc "did-finish-load" new-spare-init))
        (if spare
          (do (debug "WM using spare" spare)
              (show)
              (.send web-contents "window-id" (str window-id)))
          (.on web-contents "did-finish-load" #(js/setTimeout show 10)))
        (.on web-contents "will-navigate" handle-redirect)
        (.on web-contents "new-window" handle-redirect)
        (.once window "ready-to-show" ready)
        (.on window "blur" blur)
        (.on window "close" close)
        {:new-state new-state}))))

(defn active-window [current-state]
  (let [active (:active current-state)]
    (get-in current-state [:windows active])))

(defn relay-msg [{:keys [current-state msg-type msg-meta msg-payload]}]
  (let [window-id (or (:window-id msg-meta) :broadcast)
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
    (debug "Open dev-tools")
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

(defn set-progress [{:keys [current-state msg-payload msg-meta]}]
  (let [window-id (:window-id msg-meta)
        window-id (if (= :active window-id)
                    (:active current-state)
                    window-id)
        progress (:v msg-payload)
        dock (.-dock app)]
    (debug "Progress" progress)
    (when-let [window (get-in current-state [:windows window-id])]
      (.setProgressBar window progress))
    {}))

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

(defn hide-windows [{:keys [current-state]}]
  (doseq [[id window] (:windows current-state)]
    (.hide window))
  {})

(defn show-windows [{:keys [current-state]}]
  (doseq [[id window] (:windows current-state)]
    (.show window))
  {})

(defn activate [{:keys [current-state]}]
  (info "Activate APP")
  (when (empty? (:windows current-state))
    {:send-to-self [:window/new {:url "view.html"}]}))

(defn cmp-map [cmp-id relay-types app-path]
  (let [relay-map (zipmap relay-types (repeat relay-msg))]
    {:cmp-id      cmp-id
     :state-fn    (fn [_put-fn] {:state (atom {:app-path app-path})})
     :opts        {:in-chan  [:buffer 100]
                   :out-chan [:buffer 100]}
     :handler-map (merge relay-map
                         {:window/new       new-window
                          :window/activate  activate
                          :window/close     close-window
                          :window/minimize  minimize-window
                          :window/restore   restore-window
                          :window/hide      hide-windows
                          :window/show      show-windows
                          :window/progress  set-progress
                          :window/dev-tools dev-tools})}))
