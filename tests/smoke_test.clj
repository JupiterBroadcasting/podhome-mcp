#!/usr/bin/env bb
;; tests/smoke_test.clj
;;
;; Quick smoke test — no real API key needed.
;; Tests server startup, health endpoint, MCP handshake, and tools list.
;;
;; Run: bb tests/smoke_test.clj

(ns smoke-test
  {:clj-kondo/ignore [:duplicate-require :redefined-var :unresolved-namespace]}
  (:require [org.httpkit.server :as http-server]
            [cheshire.core :as json]
            [babashka.http-client :as http-client]
            [podhome-mcp :as podhome-mcp]))

(println "Loading podhome-mcp...")
(load-file "podhome_mcp.clj")

(def mcp-server (atom nil))
(def mcp-url (atom nil))
(def session-id (atom nil))

(defn start-mcp! []
  (let [srv (http-server/run-server
             (fn [req] (podhome-mcp/handler req))
             {:port 0 :ip "127.0.0.1"})
        port (-> srv meta :local-port)]
    (reset! mcp-server srv)
    (reset! mcp-url (str "http://127.0.0.1:" port))
    (println "  MCP server on port" port)))

(defn stop-mcp! []
  (when @mcp-server
    (@mcp-server)
    (reset! mcp-server nil)))

(defn rpc-call [method params session]
  (let [resp (http-client/post (str @mcp-url "/mcp")
                               {:headers (merge {"Content-Type" "application/json"}
                                                (when session {"mcp-session-id" session}))
                                :body (json/generate-string {:jsonrpc "2.0"
                                                             :id (rand-int 10000)
                                                             :method method
                                                             :params params})})
        headers (:headers resp)
        sid (or session (get headers "mcp-session-id"))]
    (when-not session
      (reset! session-id sid))
    (json/parse-string (:body resp) true)))

(defn call-tool [name args]
  (rpc-call "tools/call" {:name name :arguments args} @session-id))

;; ─── Tests ──────────────────────────────────────────────────────────────────

(def failures (atom 0))

(defn check [label pred]
  (if pred
    (println "  ✅" label)
    (do (println "  ❌" label)
        (swap! failures inc))))

(defn test-health []
  (print "\n[health] ")
  (let [resp (http-client/get (str @mcp-url "/health"))
        body (json/parse-string (:body resp) true)]
    (check "status 200" (= 200 (:status resp)))
    (check "body is ok" (= "ok" (:status body)))
    (check "server name" (= "podhome-mcp" (:server body)))))

(defn test-initialize []
  (print "\n[initialize] ")
  (let [resp (rpc-call "initialize" {:protocolVersion "2025-03-26"
                                     :capabilities {}
                                     :clientInfo {:name "smoke" :version "1.0"}} nil)]
    (check "jsonrpc 2.0" (= "2.0" (:jsonrpc resp)))
    (check "has result" (some? (:result resp)))
    (check "protocol version" (= "2025-03-26" (get-in resp [:result :protocolVersion])))
    (check "server name" (= "podhome-mcp" (get-in resp [:result :serverInfo :name])))
    (check "has session id" (some? @session-id))))

(defn test-tools-list []
  (print "\n[tools/list] ")
  (let [resp (rpc-call "tools/list" {} @session-id)
        tools (get-in resp [:result :tools])
        names (set (map :name tools))]
    (check "returns tools" (pos? (count tools)))
    (check (str "tool count: " (count tools)) (= 19 (count tools)))
    (check "has get-show" (contains? names "get-show"))
    (check "has create-episode" (contains? names "create-episode"))
    (check "has get-transcript" (contains? names "get-transcript"))
    (check "has get-analytics" (contains? names "get-analytics"))
    (check "all have inputSchema" (every? #(contains? % :inputSchema) tools))))

(defn test-unknown-method []
  (print "\n[error handling] ")
  (let [resp (rpc-call "does/not/exist" {} @session-id)]
    (check "returns error" (some? (:error resp)))
    (check "error code -32601" (= -32601 (get-in resp [:error :code])))))

(defn test-missing-session []
  (print "\n[session validation] ")
  (let [resp (http-client/post (str @mcp-url "/mcp")
                               {:headers {"Content-Type" "application/json"}
                                :body (json/generate-string {:jsonrpc "2.0"
                                                             :id 99
                                                             :method "tools/list"
                                                             :params {}})
                                :throw false})]
    (check "rejects bad session" (= 400 (:status resp)))))

(defn test-get-show-not-real []
  (print "\n[get-show with fake key] ")
  (let [resp (call-tool "get-show" {})]
    (check "returns result" (some? resp))
    (check "has isError field" (contains? (get-in resp [:result]) :isError))))

;; ─── Run ────────────────────────────────────────────────────────────────────

(defn -main [& _args]
  (println "\n=== Smoke Tests ===")
  (println "  (no real API key needed)")
  (start-mcp!)
  (try
    (test-health)
    (test-initialize)
    (test-tools-list)
    (test-unknown-method)
    (test-missing-session)
    (test-get-show-not-real)
    (println "\n=== Results ===")
    (if (zero? @failures)
      (println "All passed!")
      (println @failures "FAILED"))
    (finally
      (stop-mcp!)
      (System/exit (if (zero? @failures) 0 1)))))

(-main)
