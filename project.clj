(defproject fullcontact/full.http "1.0.0"
  :description "Async HTTP client and server on top of http-kit and core.async."
  :url "https://github.com/fullcontact/full.http"
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo}
  :deploy-repositories [["releases" {:url "https://clojars.org/repo/" :creds :gpg}]]
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [http-kit "2.2.0"]
                 [compojure "1.3.4" :exclusions [clj-time]]
                 [javax.servlet/servlet-api "2.5"]
                 [ring-cors "0.1.7"]
                 [fullcontact/camelsnake "0.9.0"]
                 [fullcontact/full.json "0.10.1"]
                 [fullcontact/full.metrics "0.11.4"]
                 [fullcontact/full.async "0.9.0"]
                 [fullcontact/full.core "0.10.1"]]
  :aot :all
  :release-tasks [["vcs" "assert-committed"]
                  ["change" "version" "leiningen.release/bump-version" "release"]
                  ["vcs" "commit"]
                  ["vcs" "tag" "--no-sign"]
                  ["deploy"]
                  ["change" "version" "leiningen.release/bump-version"]
                  ["vcs" "commit"]
                  ["vcs" "push"]]
  :plugins [[lein-midje "3.1.3"]]
  :profiles {:dev {:dependencies [[midje "1.7.0"]]}})
