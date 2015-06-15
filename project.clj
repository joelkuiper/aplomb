(defproject ocpu-balancer "0.1.0-SNAPSHOT"

  :description "FIXME: write description"
  :url "http://example.com/FIXME"

  :dependencies [[org.clojure/clojure "1.7.0-RC1"]
                 [selmer "0.8.2"]
                 [com.taoensso/timbre "3.4.0"]
                 [environ "1.0.0"]
                 [compojure "1.3.4"]
                 [ring/ring-defaults "0.1.5"]
                 [ring/ring-session-timeout "0.1.0"]
                 [ring-cors "0.1.7"]
                 [metosin/ring-middleware-format "0.6.0"]
                 [metosin/ring-http-response "0.6.2"]
                 [prone "0.8.2"]
                 [org.clojure/tools.nrepl "0.2.10"]
                 [com.google.guava/guava "18.0"]
                 [potemkin "0.3.13"]

                 [factual/durable-queue "0.1.5"]
                 [clj-http "1.1.2"]
                 [clojurewerkz/urly "1.0.0"]
                 [crypto-random "1.2.0"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [crouton "0.1.2"]
                 [ring/ring-codec "1.0.0"]

                 [http-kit "2.1.19"]]

  :min-lein-version "2.0.0"
  :uberjar-name "ocpu-balancer.jar"
  :jvm-opts ["-server"]

  :env {:repl-port 7001
        :port 3000
        :upstreams "http://192.168.178.27/|1,http://192.168.178.27/|1"}

  :main ocpu-balancer.core

  :plugins [[lein-environ "1.0.0"]
            [lein-ancient "0.6.5"]]

  :profiles
  {:uberjar {:omit-source true
             :env {:production true}
             :dev false
             :aot :all}
   :dev {:dependencies [[ring-mock "0.1.5"]
                        [ring/ring-devel "1.3.2"]
                        [pjstadig/humane-test-output "0.7.0"]]
         :source-paths ["env/dev/clj"]
         :repl-options {:init-ns ocpu-balancer.core}
         :injections [(require 'pjstadig.humane-test-output)
                      (pjstadig.humane-test-output/activate!)]
         :env {:dev true}}})
