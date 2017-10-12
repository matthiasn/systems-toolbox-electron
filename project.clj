(defproject matthiasn/systems-toolbox-electron "0.6.11"
  :description "Building blocks for ClojureScript systems on top of Electron"
  :url "https://github.com/matthiasn/systems-toolbox-electron"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[com.taoensso/timbre "4.10.0"]
                 [matthiasn/systems-toolbox "0.6.19"]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.9.0-beta2"]
                                  [org.clojure/clojurescript "1.9.946"]]}})
