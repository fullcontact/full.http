(ns full.http.client
  (:require [clojure.core.async :refer [go chan >! close! promise-chan put!]]
            [clojure.string :refer [upper-case]]
            [org.httpkit.client :as httpkit]
            [manifold.deferred :as d]
            [aleph.http :as aleph]
            [camelsnake.core :refer [->camelCase ->kebab-case-keyword]]
            [full.core.sugar :refer :all]
            [full.core.config :refer [opt]]
            [full.core.log :as log]
            [full.async :refer [go-try]]
            [full.json :refer [read-json write-json]]))


(def http-timeout (opt :http-timeout :default 30)) ; seconds

(def connection-error-status 503)


;;; LOGGING
;;; Each status is logged via a different logger, so that statuses
;;; can be filtered in log config

(defn logger [status]
  (-> (str "full.http.client." status)
      (org.slf4j.LoggerFactory/getLogger)))

(defmacro log-error [status & message]
  `(-> (logger ~status)
       (.error (print-str ~@message))))

(defmacro log-warn [status & message]
  `(-> (logger ~status)
       (.warn (print-str ~@message))))

(defmacro log-debug [status & message]
  `(-> (logger ~status)
       (.debug (print-str ~@message))))


;;; REQUEST / RESPONSE HANDLING

(defn json-body? [body]
  (and body (map? body)))

(defn- request-body
  [body & {:keys [json-key-fn] :or {json-key-fn ->camelCase}}]
  (if (json-body? body)
    (write-json body :json-key-fn json-key-fn)
    body))

(defn- request-headers [body headers]
  (if (json-body? body)
    (update headers "Content-Type" #(or % "application/json"))
    headers))

(defn- process-error-response
  ([full-url ex]
    (let [d (ex-data ex)]
      (process-error-response full-url (:status d) (:body d) nil)))
  ([full-url status body cause]
    (let [status (if cause connection-error-status status)
          body (if cause (str cause) body)
          message (str "Error requesting " full-url ": "
                       (if cause
                         (str "Connection error " (str cause))
                         (str "HTTP Error " status)))
          ex (ex-info message {:status status, :body body} cause)]
      (if (>= status 500)
        (log-error status message)
        (log-warn status message))
      ex)))

(defn- process-response
  [req full-url response-parser
   {:keys [opts status headers body error] :as res}]
  (try
    (if (or error (> status 399))
      (process-error-response full-url status body error)
      (let [res (if response-parser
                  (response-parser res)
                  res)]
        (log-debug status "Response " full-url
                          "status:" status
                          (when body (str "body:" body))
                          "headers:" headers)
        res))
    (catch Exception e
      (log/error e "Error parsing response")
      (ex-info (str "Error parsing response: " e) {:status 500} e))))

(defn- process-response>
  [req full-url result-channel response-parser res]
  (go
    (>! result-channel (process-response req full-url response-parser res))
    (close! result-channel)))

(defn create-json-response-parser
  [json-key-fn]
  (fn [{:keys [opts status headers body] :as res}]
    (cond
      (= :head (:method opts))
        headers
      (> status 299)
        res  ; 30x status - return response as is
      (and (not= status 204)  ; has content
           (.startsWith (:content-type headers "") "application/json"))
        (or (read-json (if (instance? java.io.InputStream body)
                         (slurp body)
                         body)
                       :json-key-fn json-key-fn)
            {})
      :else
        (or body ""))))

(def raw-json-response-parser
  (create-json-response-parser identity))

(def kebab-case-json-response-parser
  (create-json-response-parser ->kebab-case-keyword))


;;; REQUEST

(defn req>
  "Performs asynchronous API request. Always returns result channel which will
  return either response or exception."
  [{:keys [base-url resource url method params body headers basic-auth
           timeout form-params body-json-key-fn response-parser oauth-token
           follow-redirects? as files out-chan]
    :or {method (if (json-body? body) :post :get)
         body-json-key-fn ->camelCase
         response-parser kebab-case-json-response-parser
         follow-redirects? true
         as :auto}}]
  {:pre [(or url (and base-url resource))]}
  (let [req {:url (or url (str base-url "/" resource))
             :method method
             :body (request-body body :json-key-fn body-json-key-fn)
             :query-params params
             :headers (request-headers body headers)
             :multipart files
             :form-params form-params
             :basic-auth basic-auth
             :oauth-token oauth-token
             :timeout (* (or timeout @http-timeout) 1000)
             :follow-redirects follow-redirects?
             :as as}
        full-url (str (upper-case (name method))
                      " " (:url req)
                      (if (not-empty (:query-params req))
                        (str "?" (query-string (:query-params req))) ""))
        result-channel (or out-chan (promise-chan))]
    (log/debug "Request" full-url
               (if-let [body (:body req)] (str "body:" body) "")
               (if-let [headers (:headers req)] (str "headers:" headers) ""))
    (httpkit/request req (partial process-response>
                                  req
                                  full-url
                                  result-channel
                                  response-parser))
    result-channel))


;;; Aleph requests

(defn- aleph-req-uri [{:keys [base-url resource url params]}]
  {:pre [(or url (and base-url resource))]}
  (str (or url (str base-url "/" resource))
       (when params "?" (query-string params))))

(defn aleph-req
  "Performs asynchronous API request. Returns Manifold deferred which contains
   either respose or throwable with error."
  [{:keys [body method timeout form-params body-json-key-fn response-parser
           form-params follow-redirects? basic-auth oauth-token headers]
    :as req-map
    :or {body-json-key-fn ->camelCase
         response-parser kebab-case-json-response-parser
         follow-redirects? true}}]
  (let [full-url (aleph-req-uri req-map)
        method (or method (if (json-body? body) :post :get))]
    (-> (aleph/request {:request-method method
                        :body (aleph-req-body req-map)
                        :oauth-token oauth-token
                        :body (request-body body :json-key-fn body-json-key-fn)
                        :basic-auth basic-auth
                        :form-params form-params
                        :headers (request-headers body headers)
                        :follow-redirects? follow-redirects?
                        :url full-url})
        (d/timeout! (* (or timeout @http-timeout) 1000))
        (d/chain (partial process-response req-map full-url response-parser))
        (d/catch Exception #(process-error-response full-url %)))))

(defn aleph-req>
  "Performs asynchronous API request. Returns channel which contains
   either respose or throwable with error."
  [{:keys [out-chan] :as req-map}]
  (let [result-channel (or out-chan (promise-chan))
        full-url (aleph-req-uri req-map)]
    (-> (aleph-req req-map)
        (d/on-realized #(put! result-channel %)
                       #(put! result-channel (process-error-response full-url %))))
    result-channel))
