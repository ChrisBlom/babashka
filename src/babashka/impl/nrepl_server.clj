(ns babashka.impl.nrepl-server
  {:no-doc true}
  (:refer-clojure :exclude [send future binding])
  (:require [babashka.impl.bencode.core :refer [write-bencode read-bencode]]
            [clojure.stacktrace :as stacktrace]
            [clojure.string :as str]
            [sci.core :refer [future binding]]
            [sci.impl.interpreter :as sci]
            [sci.impl.vars :as vars])
  (:import [java.io OutputStream InputStream PushbackInputStream EOFException]
           [java.net ServerSocket]))

(set! *warn-on-reflection* true)

(def port 1667)
(def dev? (= "true" (System/getenv "BABASHKA_DEV")))

(defn response-for [old-msg msg]
  (let [session (get old-msg :session "none")
        m (assoc msg "session" session)
        id (get old-msg :id "unknown")
        m (assoc m "id" id)]
    m))

(defn send [^OutputStream os msg]
  (when dev? (println "Sending" msg))
  (write-bencode os msg)
  (.flush os))

(defn send-exception [os msg ex]
  (when dev? (prn "sending ex" (with-out-str (stacktrace/print-throwable ex))))
  (send os (response-for msg {"ex" (with-out-str (stacktrace/print-throwable ex))
                              "status" #{"done"}})))

(defn eval-msg [ctx o msg ns]
  (let [code-str (get msg :code)
        value (if (= :nil code-str)
               nil
               (sci/eval-string* ctx code-str))]
    (send o (response-for msg {"ns" (vars/current-ns-name)
                               "value" (pr-str value)}))
    (send o (response-for msg {"status" #{"done"}}))))

(defn register-session [ctx i o ns msg session-loop]
  (let [id (str (java.util.UUID/randomUUID))]
    (send o (response-for msg {"new-session" id "status" #{"done"}}))
    (session-loop ctx i o id ns)))

(defn read-msg [msg]
  (-> (zipmap (map keyword (keys msg))
              (map #(if (bytes? %)
                      (String. (bytes %))
                      %) (vals msg)))
      (update :op keyword)))

(defn session-loop [ctx ^InputStream is os id ns]
  (when dev? (println "Reading!" id (.available is)))
  (when-let [msg (try (read-bencode is)
                      (catch EOFException _
                        (println "Client closed connection.")))]
    (let [msg (read-msg msg)]
      (when dev? (prn "Received" msg))
      (case (get msg :op)
        :clone (do
                 (when dev? (println "Cloning!"))
                 (register-session ctx is os ns msg session-loop))
        :eval (do
                (try (eval-msg ctx os msg ns)
                     (catch Exception exn
                       (send-exception os msg exn)))
                (recur ctx is os id ns))
        :describe
        (do (send os (response-for msg {"status" #{"done"}
                                        "aux" {}
                                        "ops" (zipmap #{"clone", "describe", "eval"}
                                                      (repeat {}))
                                        "versions" {} #_{"nrepl" {"major" "0"
                                                             "minor" "4"
                                                             "incremental" "0"
                                                             "qualifier" ""}
                                                    "clojure"
                                                    {"*clojure-version*"
                                                     (zipmap (map name (keys *clojure-version*))
                                                             (vals *clojure-version*))}}}))
            (recur ctx is os id ns))
        (do (when dev?
              (println "Unhandled message" msg))
            (send os (response-for msg {"status" #{"error" "unknown-op" "done"}})))))))

(defn listen [ctx ^ServerSocket listener]
  (when dev? (println "Listening"))
  (let [client-socket (.accept listener)
        in (.getInputStream client-socket)
        in (PushbackInputStream. in)
        out (.getOutputStream client-socket)]
    (when dev? (println "Connected."))
    (future
      (binding
          ;; allow *ns* to be set! inside future
          [vars/current-ns (vars/->SciNamespace 'user nil)]
        (session-loop ctx in out "pre-init" *ns*)))
    (recur ctx listener)))

(defn start-server! [ctx host+port]
  (let [parts (str/split host+port #":")
        [address port] (if (= 1 (count parts))
                      [nil (Integer. ^String (first parts))]
                      [(first parts) (Integer. ^String (second parts))])
        host+port (if-not address (str "localhost:" port)
                          host+port)]
    (println "Starting nREPL at" host+port)
    (listen ctx (new ServerSocket port 0 address))))
