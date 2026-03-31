#!/usr/bin/env bb
;; tests/test_podhome_mcp.clj
;;
;; Integration tests for podhome-mcp using the REAL Podhome API.
;;
;; Run: cd podhome-mcp && PODHOME_API_KEY=your-key bb tests/test_podhome_mcp.clj

(ns test-podhome-mcp
  {:clj-kondo/ignore [:duplicate-require :redefined-var]}
  (:require [org.httpkit.server :as http-server]
            [cheshire.core :as json]
            [babashka.http-client :as http-client]
            [podhome-mcp :as podhome-mcp]))

(println "Loading podhome-mcp...")
(load-file "podhome_mcp.clj")

(def test-api-key (System/getenv "PODHOME_API_KEY"))
(def test-port 0)

(def mcp-server (atom nil))
(def mcp-url (atom nil))
(def session-id (atom nil))

(defn start-mcp! []
  (println "Starting MCP server...")
  (let [srv (http-server/run-server
             (fn [req] (podhome-mcp/handler req))
             {:port test-port :ip "127.0.0.1"})
        port (-> srv meta :local-port)]
    (reset! mcp-server srv)
    (reset! mcp-url (str "http://127.0.0.1:" port))
    (println "MCP server on port" port)))

(defn stop-mcp! []
  (when @mcp-server
    (println "Stopping MCP server...")
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

(defn initialize []
  (let [resp (rpc-call "initialize" {:protocolVersion "2025-03-26"
                                     :capabilities {}
                                     :clientInfo {:name "test" :version "1.0"}}
                       nil)
        sid (get-in resp [:headers "mcp-session-id"])]
    (reset! session-id sid)
    (println "Session:" sid)
    sid))

(defn call-tool [tool-name args]
  (rpc-call "tools/call" {:name tool-name :arguments args} @session-id))

;; ─── Tests ──────────────────────────────────────────────────────────────────

(defn test-initialize []
  (print "Testing initialize... ")
  (let [resp (rpc-call "initialize" {:protocolVersion "2025-03-26"} nil)]
    (if (and (= "2.0" (:jsonrpc resp))
             (= "podhome-mcp" (get-in resp [:result :serverInfo :name])))
      (println "OK")
      (println "FAIL:" resp))))

(defn test-health []
  (print "Testing health endpoint... ")
  (let [resp (babashka.http-client/get (str @mcp-url "/health"))]
    (if (= 200 (:status resp))
      (println "OK")
      (println "FAIL:" resp))))

(defn test-get-show []
  (print "Testing get-show tool... ")
  (let [result (call-tool "get-show" {})]
    (if (nil? (get-in result [:error]))
      (println "OK")
      (println "FAIL:" result))))

(defn test-list-episodes []
  (print "Testing list-episodes tool... ")
  (let [result (call-tool "list-episodes" {})]
    (if (nil? (get-in result [:error]))
      (println "OK")
      (println "FAIL:" result))))

(defn test-tools-list []
  (print "Testing tools/list... ")
  (let [result (rpc-call "tools/list" {} @session-id)
        tools (get-in result [:result :tools])
        tool-names (map :name tools)]
    (if (and (some #{"get-show"} tool-names)
             (some #{"create-episode"} tool-names)
             (some #{"get-transcript"} tool-names))
      (println "OK")
      (println "FAIL - missing tools"))))

;; Run all tests
(defn run-all-tests []
  (when-not test-api-key
    (throw (Exception. "PODHOME_API_KEY env var required")))

  (start-mcp!)
  (initialize)

  (try
    (test-initialize)
    (test-health)
    (test-get-show)
    (test-list-episodes)
    (test-tools-list)
    (println "\nAll tests passed!")
    (finally
      (stop-mcp!))))

(run-all-tests)
