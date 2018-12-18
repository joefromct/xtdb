(ns crux.kv.lmdb
  "LMDB KV backend for Crux.

  Requires org.lwjgl/lwjgl-lmdb and org.lwjgl/lwjgl on the classpath,
  including native dependencies."
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.spec.alpha :as s]
            [crux.byte-utils :as bu]
            [crux.io :as cio]
            [crux.kv :as kv])
  (:import [clojure.lang ExceptionInfo MapEntry]
           java.io.Closeable
           [org.lwjgl.system MemoryStack MemoryUtil]
           [org.lwjgl.util.lmdb LMDB MDBEnvInfo MDBStat MDBVal]))

(set! *unchecked-math* :warn-on-boxed)

;; Based on
;; https://github.com/LWJGL/lwjgl3/blob/master/modules/samples/src/test/java/org/lwjgl/demo/util/lmdb/LMDBDemo.java

(defn- success? [rc]
  (when-not (= LMDB/MDB_SUCCESS rc)
    (throw (ex-info (LMDB/mdb_strerror rc) {:error rc}))))

(defn- env-set-mapsize [^long env ^long size]
  (success? (LMDB/mdb_env_set_mapsize env size)))

;; TODO: Note, this has to be done when there are no open
;; transactions. Also, when file reached 4Gb it crashed. MDB_WRITEMAP
;; and MDB_MAPASYNC might solve this, but doesn't allow nested
;; transactions. See: https://github.com/dw/py-lmdb/issues/113
(defn- increase-mapsize [env ^long factor]
  (with-open [stack (MemoryStack/stackPush)]
    (let [info (MDBEnvInfo/callocStack stack)]
      (success? (LMDB/mdb_env_info env info))
      (let [new-mapsize (* factor (.me_mapsize info))]
        (log/debug "Increasing mapsize to:" new-mapsize)
        (env-set-mapsize env new-mapsize)))))

(defrecord LMDBTransaction [^long txn]
  Closeable
  (close [_]
    (let [rc (LMDB/mdb_txn_commit txn)]
      (when-not (= LMDB/MDB_BAD_TXN rc)
        (success? rc)))))

(defn- ^LMDBTransaction new-transaction [^MemoryStack stack env flags]
  (let [pp (.mallocPointer stack 1)
        rc (LMDB/mdb_txn_begin env MemoryUtil/NULL flags pp)]
    (if (= LMDB/MDB_MAP_RESIZED rc)
      (env-set-mapsize env 0)
      (success? rc))
    (->LMDBTransaction (.get pp))))

(defn- env-create []
  (with-open [stack (MemoryStack/stackPush)]
    (let [pp (.mallocPointer stack 1)]
      (success? (LMDB/mdb_env_create pp))
      (.get pp))))

(defn- env-open [^long env dir ^long flags]
  (success? (LMDB/mdb_env_open env
                               (.getAbsolutePath (doto (io/file dir)
                                                   (.mkdirs)))
                               flags
                               0664)))

(defn- env-close [^long env]
  (LMDB/mdb_env_close env))

(defn- env-copy [^long env path]
  (let [file (io/file path)]
    (when (.exists file)
      (throw (IllegalArgumentException. (str "Directory exists: " (.getAbsolutePath file)))))
    (.mkdirs file)
    (success? (LMDB/mdb_env_copy env (.getAbsolutePath file)))))

(defn- dbi-open [env]
  (with-open [stack (MemoryStack/stackPush)
              tx (new-transaction stack env LMDB/MDB_RDONLY)]
    (let [{:keys [^long txn]} tx
          ip (.mallocInt stack 1)
          ^CharSequence name nil]
      (success? (LMDB/mdb_dbi_open txn  name 0 ip))
      (.get ip 0))))

(defrecord LMDBCursor [^long cursor]
  Closeable
  (close [_]
    (LMDB/mdb_cursor_close cursor)))

(defn- ^LMDBCursor new-cursor [^MemoryStack stack dbi txn]
  (with-open [stack (.push stack)]
    (let [pp (.mallocPointer stack 1)]
      (success? (LMDB/mdb_cursor_open txn dbi pp))
      (->LMDBCursor (.get pp)))))

(defn- cursor->key [cursor ^MDBVal kv ^MDBVal dv flags]
  (let [rc (LMDB/mdb_cursor_get cursor kv dv flags)]
    (when (not= LMDB/MDB_NOTFOUND rc)
      (success? rc)
      (bu/byte-buffer->bytes (.mv_data kv)))))

(defn- cursor-put [env dbi kvs]
  (with-open [stack (MemoryStack/stackPush)
              tx (new-transaction stack env 0)
              cursor (new-cursor stack dbi (:txn tx))]
    (let [{:keys [cursor]} cursor
          kv (MDBVal/mallocStack stack)
          dv (MDBVal/mallocStack stack)]
      (doseq [[^bytes k ^bytes v] kvs]
        (with-open [stack (.push stack)]
          (let [kb (.flip (.put (.malloc stack (alength k)) k))
                kv (.mv_data kv kb)
                dv (.mv_size dv (alength v))]
            (success? (LMDB/mdb_cursor_put cursor kv dv LMDB/MDB_RESERVE))
            (.put (.mv_data dv) v)))))))

(defn- tx-delete [env dbi ks]
  (with-open [stack (MemoryStack/stackPush)
              tx (new-transaction stack env 0)]
    (let [{:keys [^long txn]} tx
          kv (MDBVal/callocStack stack)]
      (doseq [^bytes k (sort bu/bytes-comparator ks)]
        (with-open [stack (.push stack)]
          (let [kb (.flip (.put (.malloc stack (alength k)) k))
                kv (.mv_data kv kb)
                rc (LMDB/mdb_del txn dbi kv nil)]
            (when-not (= LMDB/MDB_NOTFOUND rc)
              (success? rc))))))))

(def default-env-flags (bit-or LMDB/MDB_WRITEMAP
                               LMDB/MDB_MAPASYNC
                               LMDB/MDB_NOTLS
                               LMDB/MDB_NORDAHEAD))

(defrecord LMDBKvIterator [^LMDBCursor cursor ^LMDBTransaction tx ^MDBVal kv ^MDBVal dv]
  kv/KvIterator
  (seek [_ k]
    (with-open [stack (MemoryStack/stackPush)]
      (let [k ^bytes k
            kb (.flip (.put (.malloc stack (alength k)) k))
            kv (.mv_data kv kb)]
        (cursor->key (:cursor cursor) kv dv LMDB/MDB_SET_RANGE))))

  (next [this]
    (cursor->key (:cursor cursor) kv dv LMDB/MDB_NEXT))

  (value [this]
    (bu/byte-buffer->bytes (.mv_data dv)))

  (refresh [this]
    (LMDB/mdb_cursor_renew (:txn tx) (:cursor cursor))
    this)

  Closeable
  (close [_]
    (.close cursor)))

(defrecord LMDBKvSnapshot [env dbi ^LMDBTransaction tx]
  kv/KvSnapshot
  (new-iterator [_]
    (with-open [stack (MemoryStack/stackPush)]
      (->LMDBKvIterator (new-cursor stack dbi (:txn tx))
                        tx
                        (MDBVal/create)
                        (MDBVal/create))))

  Closeable
  (close [_]
    (.close tx)))

(s/def ::env-flags nat-int?)

(s/def ::options (s/keys :req-un [:crux.kv/db-dir]
                         :opt [::env-flags]))

(def ^:dynamic ^{:tag 'long} *mapsize-increase-factor* 1)
(def ^:const max-mapsize-increase-factor 32)

(defrecord LMDBKv [db-dir env env-flags dbi]
  kv/KvStore
  (open [this {:keys [db-dir crux.kv.lmdb/env-flags] :as options}]
    (s/assert ::options options)
    (let [env-flags (or env-flags default-env-flags)
          env (env-create)]
      (try
        (env-open env db-dir env-flags)
        (assoc this
               :db-dir db-dir
               :env env
               :env-flags env-flags
               :dbi (dbi-open env))
        (catch Throwable t
          (env-close env)
          (throw t)))))

  (new-snapshot [_]
    (with-open [stack (MemoryStack/stackPush)]
      (let [tx (new-transaction stack env LMDB/MDB_RDONLY)]
        (->LMDBKvSnapshot env dbi tx))))

  (store [this kvs]
    (try
      (cursor-put env dbi kvs)
      (catch ExceptionInfo e
        (if (= LMDB/MDB_MAP_FULL (:error (ex-data e)))
          (binding [*mapsize-increase-factor* (* 2 *mapsize-increase-factor*)]
            (when (> *mapsize-increase-factor* max-mapsize-increase-factor)
              (throw (IllegalStateException. "Too large size of key values to store at once.")))
            (increase-mapsize env *mapsize-increase-factor*)
            (kv/store this kvs))
          (throw e)))))

  (delete [_ ks]
    (try
      (tx-delete env dbi ks)
      (catch ExceptionInfo e
        (if (= LMDB/MDB_MAP_FULL (:error (ex-data e)))
          (do (increase-mapsize env 2)
              (tx-delete env dbi ks))
          (throw e)))))

  (backup [_ dir]
    (env-copy env dir))

  (count-keys [_]
    (with-open [stack (MemoryStack/stackPush)
                tx (new-transaction stack env LMDB/MDB_RDONLY)]
      (let [stat (MDBStat/callocStack stack)]
        (LMDB/mdb_stat (:txn tx) dbi stat)
        (.ms_entries stat))))

  (db-dir [this]
    (str db-dir))

  (kv-name [_]
    "crux.rocksdb.LMDBKv")

  Closeable
  (close [_]
    (env-close env)))
