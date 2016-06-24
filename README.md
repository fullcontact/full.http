# full.http

[![Clojars Project](https://img.shields.io/clojars/v/fullcontact/full.http.svg)](https://clojars.org/fullcontact/full.http)

Async HTTP client and server on top of http-kit and core.async


## Configuration

The following configuration options are possible (via [full.core](https://github.com/fullcontact/full.core) config
loader):

```yaml
http-timeout: 30 # defaults to 30 (http client request timeout in seconds)
```

## Client

`full.http.client` extends [http-kit](http://www.http-kit.org/client.html)'s
`httpkit.client/reqest` method and returns the value in a promise channel with
an optional response parser.

```clojure
(defn github-userinfo> [username]
  (full.http.client/req>
    {:base-url "https://api.github.com"
     :resource (str "users/" username)}))
```

Default response parser will transform response fields to `:kebab-cased`
keywords.

HTTP error handling can be done with extra core.async methods provided by
[full.async](https://github.com/fullcontact/full.async):

```clojure
(def github-userinfo> [username]
  (full.async/go-try
    (try
      (<?
        (full.http.client/req>
          {:base-url "https://api.github.com"
           :resource (str "users/" username)}))
      (catch Exception e
        (log/error "user not found")))))
```

### Logging

Responses for each HTTP status are logged in a separate logger, so you can control
loglevels for them separately. All of the loggers follow the
`full.http.client.$status` naming pattern.


## Server

A minimal server example [can be found here](https://github.com/fullcontact/full.bootstrap/blob/master/examples/http-service/src/example/api.clj).

Everything is the same as you'd expect from a [stock http-kit + compojure](http://www.http-kit.org/server.html#routing) server
with the addition that you can return channels as well.


### Metrics

If you enable [full.metrics](https://github.com/fullcontact/full.metrics), `full.http.server` will report all endpoint
response times to Riemann. The following Riemann can be used to get 95th
percentile data on all endpoints (value for `tagged` is whatever you have in
the `[tags]` array in metrics configuration):

```
service =~ "endpoint.%/%0.95" and tagged "service-name" and host = nil
```

Service name for each endpoint follows the `endpoint.$method.$route` naming
scheme, so it's possible to filter requests by method and/or path.
