#!/usr/bin/env bb
;; podhome_mcp.clj — Podhome MCP Server (Streamable HTTP)
;;
;; Serves the Podhome Integration API as an MCP server.
;; Transport: Streamable HTTP (single /mcp POST endpoint)
;; Auth: X-API-KEY header via PODHOME_API_KEY env var
;;
;; Usage:
;;   PODHOME_API_KEY=your-key bb podhome_mcp.clj
;;
;; mcp-servers.edn entry:
;;   {:podhome {:cmd ["bb" "/path/to/podhome_mcp.clj"]
;;              :env {"PODHOME_API_KEY" "your-key-here"}}}

(ns podhome-mcp
  (:require [org.httpkit.server :as http]
            [babashka.http-client :as http-client]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]))

;; ─── Configuration ──────────────────────────────────────────────────────────

(def default-api-base "https://serve.podhome.fm")
(def protocol-version "2025-03-26")
(def server-info {:name "podhome-mcp" :version "0.1.0"})

(defn load-config-file []
  (let [path (str (System/getProperty "user.home") "/.config/podhome/config.edn")]
    (when (.exists (java.io.File. path))
      (clojure.edn/read-string (slurp path)))))

(defn get-config []
  (let [file-cfg (load-config-file)]
    {:api-base (or (System/getenv "PODHOME_API_BASE")
                   (:api-base file-cfg)
                   default-api-base)
     :api-key (or (System/getenv "PODHOME_API_KEY")
                  (:api-key file-cfg)
                  (throw (Exception. "PODHOME_API_KEY env var or config file required")))}))

(def api-base (:api-base (get-config)))
(def api-key (:api-key (get-config)))

;; ─── Logging ───────────────────────────────────────────────────────────────

(defn log [level message data]
  (let [output (json/generate-string
                {:timestamp (str (java.time.Instant/now))
                 :level level
                 :message message
                 :data data})]
    (if (contains? #{"error" "warn"} level)
      (binding [*out* *err*] (println output))
      (println output))))

;; ─── Session Management ─────────────────────────────────────────────────────

(def sessions (atom {}))

(defn new-session-id []
  (str (java.util.UUID/randomUUID)))

(defn create-session! []
  (let [sid (new-session-id)]
    (swap! sessions assoc sid {:created-at (System/currentTimeMillis)})
    sid))

(defn valid-session? [sid]
  (boolean (and sid (contains? @sessions sid))))

(defn find-header [request header-name]
  (let [headers (:headers request)
        low-name (str/lower-case header-name)]
    (or (get headers low-name)
        (get headers (keyword low-name))
        (some (fn [[k v]] (when (= low-name (str/lower-case (name k))) v)) headers))))

;; ─── HTTP Client ────────────────────────────────────────────────────────────

(defn api-request! [method path query-params body]
  (let [url (str api-base path)
        headers {"Accept" "application/json"
                 "Content-Type" "application/json"
                 "X-API-KEY" api-key}
        opts (cond-> {:method method
                      :uri url
                      :headers headers
                      :throw false}
               (seq query-params) (assoc :query-params query-params)
               body (assoc :body (json/generate-string body)))]
    (log "debug" "API Request" {:method method :path path :query-params query-params})
    (try
      (let [start (System/currentTimeMillis)
            resp (http-client/request opts)
            elapsed (- (System/currentTimeMillis) start)
            status (:status resp)
            resp-body (:body resp)
            body (when-not (str/blank? resp-body)
                   (try (json/parse-string resp-body true)
                        (catch Exception _ {:raw resp-body})))]
        (log "debug" "API Response" {:method method :path path :status status :elapsed_ms elapsed})
        (if (>= status 400)
          {:error true
           :status status
           :message (or (:message body) (:raw body) (str "HTTP " status))
           :body body}
          {:data body :status status :uri (:uri resp)}))
      (catch Exception e
        (log "error" "API Exception" {:method method :path path :error (.getMessage e)})
        {:error true :message (.getMessage e)}))))

(defn api-get
  ([path] (api-get path {}))
  ([path params] (api-request! :get path params nil)))

(defn api-post [path body]
  (api-request! :post path {} body))

(defn api-put [url body]
  (log "debug" "Upload Request" {:url url})
  (try
    (let [resp (http-client/request {:method :put
                                     :uri url
                                     :headers {"Content-Type" "application/octet-stream"
                                               "Content-Length" (str (count (:body body)))}
                                     :body body
                                     :throw false})]
      (if (>= (:status resp) 400)
        {:error true :status (:status resp) :message (:body resp)}
        {:success true}))
    (catch Exception e
      {:error true :message (.getMessage e)})))

;; ─── Utilities ──────────────────────────────────────────────────────────────

(defn strip-html [s]
  (when-not (str/blank? s)
    (-> s
        (str/replace #"<[^>]+>" " ")
        (str/replace #"&\w+;" " ")
        (str/replace #"\s+" " ")
        str/trim)))

(def episode-slim-keys
  [:episode_id :title :status :publish_date :episode_nr :season_nr :duration])

(defn slim-episode [ep]
  (select-keys ep episode-slim-keys))

;; ─── Tool Implementations ──────────────────────────────────────────────────

(defn tool-get-show []
  (let [resp (api-get "/api/show")]
    (if (:error resp)
      resp
      (:data resp))))

(defn tool-list-episodes [{:keys [status limit offset fields]}]
  (let [limit (min (or limit 20) 100)
        offset (or offset 0)
        full? (= fields "full")
        params (when status {:status status})
        resp (api-get "/api/episodes" params)]
    (if (:error resp)
      resp
      (let [all (:data resp)
            total (count all)
            page (->> all
                      (drop offset)
                      (take limit))
            episodes (if full? page (map slim-episode page))]
        {:episodes episodes
         :total total
         :offset offset
         :limit limit
         :has_more (< (+ offset limit) total)}))))

(defn tool-get-episode [{:keys [episode_id]}]
  (when (str/blank? episode_id) (throw (ex-info "episode_id is required" {:type :bad-request})))
  (let [resp (api-get "/api/episode" {:episode_id episode_id})]
    (if (:error resp)
      resp
      (:data resp))))

(defn tool-create-episode [{:keys [file_url title description link publish_date
                                   use_podhome_ai suggest_chapters suggest_details
                                   suggest_clips enhance_audio]}]
  (when (str/blank? title) (throw (ex-info "title is required" {:type :bad-request})))
  (let [body (into {} (filter (fn [[_ v]] (some? v)))
                   {:title title
                    :file_url file_url
                    :description description
                    :link link
                    :publish_date publish_date
                    :use_podhome_ai use_podhome_ai
                    :suggest_chapters suggest_chapters
                    :suggest_details suggest_details
                    :suggest_clips suggest_clips
                    :enhance_audio enhance_audio})
        resp (api-post "/api/createepisode" body)]
    (if (:error resp)
      resp
      {:episodeId (:episodeId (:data resp))
       :message "Episode created as draft. Poll get-episode for processing status."})))

(defn tool-modify-episode [{:keys [episode_id title description episode_nr season_nr image_url alternate_enclosure_url alternate_enclosure_type]}]
  (when (str/blank? episode_id) (throw (ex-info "episode_id is required" {:type :bad-request})))
  (let [body (into {} (filter (fn [[_ v]] (some? v)))
                   {:episode_id episode_id
                    :title title
                    :description description
                    :episode_nr episode_nr
                    :season_nr season_nr
                    :image_url image_url
                    :alternate_enclosure_url alternate_enclosure_url
                    :alternate_enclosure_type alternate_enclosure_type})
        resp (api-post "/api/modify_episode" body)]
    (if (:error resp)
      resp
      (:data resp))))

(defn tool-schedule-episode [{:keys [episode_id publish_now publish_date]}]
  (when (str/blank? episode_id) (throw (ex-info "episode_id is required" {:type :bad-request})))
  (let [body (cond-> {:episode_id episode_id}
               publish_now (assoc :publish_now true)
               publish_date (assoc :publish_date publish_date))
        resp (api-post "/api/schedule_episode" body)]
    (if (:error resp)
      resp
      {:episode_id (:episode_id (:data resp))
       :publish_date (:publish_date (:data resp))
       :status (:status (:data resp))
       :message (if publish_now "Episode published!" "Episode scheduled!")})))

(defn tool-begin-upload [{:keys [title file_name file_size description link publish_date enhance_audio]}]
  (when (str/blank? title) (throw (ex-info "title is required" {:type :bad-request})))
  (when (str/blank? file_name) (throw (ex-info "file_name is required" {:type :bad-request})))
  (when (nil? file_size) (throw (ex-info "file_size is required" {:type :bad-request})))
  (let [body (into {} (filter (fn [[_ v]] (some? v)))
                   {:title title
                    :file_name file_name
                    :file_size file_size
                    :description description
                    :link link
                    :publish_date publish_date
                    :enhance_audio enhance_audio})
        resp (api-post "/api/begin_upload" body)]
    (if (:error resp)
      resp
      {:upload_url (:upload_url (:data resp))
       :blob_name (:blob_name (:data resp))
       :episode_id (:episode_id (:data resp))
       :message "Upload URL obtained. PUT file to upload_url, then call finalize-upload."})))

(defn tool-finalize-upload [{:keys [episode_id blob_name file_size use_podhome_ai suggest_chapters suggest_details suggest_clips]}]
  (when (str/blank? episode_id) (throw (ex-info "episode_id is required" {:type :bad-request})))
  (when (str/blank? blob_name) (throw (ex-info "blob_name is required" {:type :bad-request})))
  (let [body (into {} (filter (fn [[_ v]] (some? v)))
                   {:episode_id episode_id
                    :blob_name blob_name
                    :file_size file_size
                    :use_podhome_ai use_podhome_ai
                    :suggest_chapters suggest_chapters
                    :suggest_details suggest_details
                    :suggest_clips suggest_clips})
        resp (api-post "/api/finalize_upload" body)]
    (if (:error resp)
      resp
      {:episode_id (:episode_id (:data resp))
       :status (:status (:data resp))
       :message "Upload finalized. Poll get-episode for processing status."})))

(defn tool-upload-and-create [{:keys [file_path title use_podhome_ai]}]
  (let [file (java.io.File. file_path)]
    (when-not (.exists file) (throw (ex-info (str "File not found: " file_path) {:type :not-found})))
    (let [file-size (.length file)
          file-name (.getName file)
          ;; Step 1: begin upload
          begin (tool-begin-upload {:title title :file_name file-name :file_size file-size})
          _ (when (:error begin) (throw (ex-info "begin-upload failed" {:cause begin})))
          ;; Step 2: upload file
          upload-result (api-put (:upload_url begin) (slurp file_path))
          _ (when (:error upload-result) (throw (ex-info "file upload failed" {:cause upload-result})))
          ;; Step 3: finalize
          final (tool-finalize-upload {:episode_id (:episode_id begin)
                                       :blob_name (:blob_name begin)
                                       :file_size file-size
                                       :use_podhome_ai use_podhome_ai})]
      (if (:error final)
        final
        {:episode_id (:episode_id final)
         :status (:status final)
         :message "Upload complete. Poll get-episode for processing status."}))))

(defn tool-delete-episode [{:keys [episode_id confirm]}]
  (when (str/blank? episode_id) (throw (ex-info "episode_id is required" {:type :bad-request})))
  (when-not confirm (throw (ex-info "confirm must be true to delete an episode" {:type :bad-request})))
  (let [resp (api-post "/api/delete_episode" {:episode_id episode_id})]
    (if (:error resp)
      resp
      {:message (str "Episode " episode_id " deleted")})))

;; Chapters

(defn tool-create-chapter [{:keys [episode_id title start_time description image_url url]}]
  (when (str/blank? episode_id) (throw (ex-info "episode_id is required" {:type :bad-request})))
  (when (str/blank? title) (throw (ex-info "title is required" {:type :bad-request})))
  (when (nil? start_time) (throw (ex-info "start_time is required" {:type :bad-request})))
  (let [body (into {} (filter (fn [[_ v]] (some? v)))
                   {:episode_id episode_id
                    :title title
                    :start_time start_time
                    :description description
                    :image_url image_url
                    :url url})
        resp (api-post "/api/create_chapter" body)]
    (if (:error resp)
      resp
      {:chapter_id (:chapter_id (:data resp))
       :message "Chapter created"})))

(defn tool-modify-chapter [{:keys [chapter_id title start_time description image_url url]}]
  (when (str/blank? chapter_id) (throw (ex-info "chapter_id is required" {:type :bad-request})))
  (let [body (into {} (filter (fn [[_ v]] (some? v)))
                   {:chapter_id chapter_id
                    :title title
                    :start_time start_time
                    :description description
                    :image_url image_url
                    :url url})
        resp (api-post "/api/modify_chapter" body)]
    (if (:error resp)
      resp
      (:data resp))))

(defn tool-delete-chapter [{:keys [episode_id chapter_id]}]
  (when (str/blank? episode_id) (throw (ex-info "episode_id is required" {:type :bad-request})))
  (when (str/blank? chapter_id) (throw (ex-info "chapter_id is required" {:type :bad-request})))
  (let [resp (api-post "/api/delete_chapter" {:episode_id episode_id :chapter_id chapter_id})]
    (if (:error resp)
      resp
      {:message (str "Chapter " chapter_id " deleted")})))

;; Chapters - List

(defn tool-get-chapters [{:keys [episode_id limit offset]}]
  (when (str/blank? episode_id) (throw (ex-info "episode_id is required" {:type :bad-request})))
  (let [limit (min (or limit 50) 100)
        offset (or offset 0)
        resp (api-get "/api/chapters" {:episode_id episode_id})]
    (if (:error resp)
      resp
      (let [all (:data resp)
            total (count all)
            page (->> all (drop offset) (take limit))]
        {:chapters page
         :total total
         :offset offset
         :limit limit
         :has_more (< (+ offset limit) total)}))))

;; Clips

(defn tool-list-clips [{:keys [episode_id limit offset]}]
  (when (str/blank? episode_id) (throw (ex-info "episode_id is required" {:type :bad-request})))
  (let [limit (min (or limit 50) 100)
        offset (or offset 0)
        resp (api-get "/api/clips" {:episode_id episode_id})]
    (if (:error resp)
      resp
      (let [all (:data resp)
            total (count all)
            page (->> all (drop offset) (take limit))]
        {:clips page
         :total total
         :offset offset
         :limit limit
         :has_more (< (+ offset limit) total)}))))

(defn tool-create-clip [{:keys [episode_id title start_time duration]}]
  (when (str/blank? episode_id) (throw (ex-info "episode_id is required" {:type :bad-request})))
  (when (str/blank? title) (throw (ex-info "title is required" {:type :bad-request})))
  (when (nil? start_time) (throw (ex-info "start_time is required" {:type :bad-request})))
  (when (nil? duration) (throw (ex-info "duration is required" {:type :bad-request})))
  (let [body {:episode_id episode_id :title title :start_time start_time :duration duration}
        resp (api-post "/api/createclip" body)]
    (if (:error resp)
      resp
      {:clip_id (:clip_id (:data resp))
       :message "Clip created"})))

(defn tool-modify-clip [{:keys [clip_id title start_time duration]}]
  (when (str/blank? clip_id) (throw (ex-info "clip_id is required" {:type :bad-request})))
  (let [body (into {} (filter (fn [[_ v]] (some? v)))
                   {:clip_id clip_id :title title :start_time start_time :duration duration})
        resp (api-post "/api/modify_clip" body)]
    (if (:error resp)
      resp
      (:data resp))))

(defn tool-delete-clip [{:keys [episode_id clip_id]}]
  (when (str/blank? episode_id) (throw (ex-info "episode_id is required" {:type :bad-request})))
  (when (str/blank? clip_id) (throw (ex-info "clip_id is required" {:type :bad-request})))
  (let [resp (api-post "/api/delete_clip" {:episode_id episode_id :clip_id clip_id})]
    (if (:error resp)
      resp
      {:message (str "Clip " clip_id " deleted")})))

;; Transcript

(defn tool-get-transcript [{:keys [episode_id]}]
  (when (str/blank? episode_id) (throw (ex-info "episode_id is required" {:type :bad-request})))
  (let [resp (api-get "/api/transcript" {:episode_id episode_id})]
    (if (:error resp)
      resp
      (:data resp))))

;; Analytics

(defn tool-get-analytics [{:keys [period episode_id dimension metric granularity]}]
  (let [params (into {} (filter (fn [[_ v]] (some? v)))
                     {:period period
                      :episode_id episode_id
                      :dimension dimension
                      :metric metric
                      :granularity granularity})
        resp (api-get "/api/analytics" params)]
    (if (:error resp)
      resp
      (:data resp))))

;; ─── Tool Definitions ───────────────────────────────────────────────────────

(def tools
  [{:name "get-show"
    :description "Get show metadata: title, description, author, image, feed URL."
    :inputSchema {:type "object" :properties {} :required []}}

   {:name "list-episodes"
    :description "List podcast episodes. Returns slim fields by default (id, title, status, date). Use fields=\"full\" for all data. Paginate with limit/offset."
    :inputSchema {:type "object"
                  :properties {:status {:type "string" :description "Filter by status: 0=draft, 1=scheduled, 2=published"}
                               :limit {:type "integer" :description "Max episodes to return (default 20, max 100)"}
                               :offset {:type "integer" :description "Skip N episodes (for pagination)"}
                               :fields {:type "string" :description "Set to \"full\" for all fields including description, chapters, etc"}}
                  :required []}}

   {:name "get-episode"
    :description "Get full details for a single episode. Use to poll processing status after upload."
    :inputSchema {:type "object"
                  :properties {:episode_id {:type "string" :description "Episode UUID"}}
                  :required ["episode_id"]}}

   {:name "create-episode"
    :description "Create a new episode from a publicly accessible audio URL. Episode is created as Draft unless publish_date is set."
    :inputSchema {:type "object"
                  :properties {:file_url {:type "string" :description "Public URL to audio file"}
                               :title {:type "string" :description "Episode title"}
                               :description {:type "string" :description "Show notes (HTML allowed)"}
                               :link {:type "string" :description "Canonical link"}
                               :publish_date {:type "string" :description "ISO-8601 UTC datetime, e.g. 2025-08-01T14:00:00Z"}
                               :use_podhome_ai {:type "boolean" :description "Run Podhome AI (transcript, chapters, etc)"}
                               :suggest_chapters {:type "boolean" :description "Auto-generate chapters (requires use_podhome_ai)"}
                               :suggest_details {:type "boolean" :description "Auto-generate description/title"}
                               :suggest_clips {:type "boolean" :description "Auto-generate clips"}
                               :enhance_audio {:type "boolean" :description "Audio enhancement (paid)"}}
                  :required ["title"]}}

   {:name "modify-episode"
    :description "Update episode metadata. Add alternate audio/video with alternate_enclosure_url. Only include fields to change. Description accepts HTML."
    :inputSchema {:type "object"
                  :properties {:episode_id {:type "string" :description "Episode UUID (required)"}
                               :title {:type "string" :description "Episode title"}
                               :description {:type "string" :description "Show notes (HTML)"}
                               :episode_nr {:type "integer" :description "Episode number"}
                               :season_nr {:type "integer" :description "Season number"}
                               :image_url {:type "string" :description "Cover image URL"}
                               :alternate_enclosure_url {:type "string" :description "Alternate audio/video URL (e.g. video version)"}
                               :alternate_enclosure_type {:type "string" :description "MIME type for alternate (e.g. video/mp4, audio/aac)"}}
                  :required ["episode_id"]}}

   {:name "schedule-episode"
    :description "Publish an episode immediately or schedule for a future date."
    :inputSchema {:type "object"
                  :properties {:episode_id {:type "string" :description "Episode UUID"}
                               :publish_now {:type "boolean" :description "Publish immediately"}
                               :publish_date {:type "string" :description "ISO-8601 datetime (ignored if publish_now=true)"}}
                  :required ["episode_id"]}}

   {:name "begin-upload"
    :description "Start a direct upload. ⚠️ BROKEN - returns 400 error. Use create-episode with file_url instead. Returns upload_url, blob_name, episode_id."
    :inputSchema {:type "object"
                  :properties {:title {:type "string" :description "Episode title"}
                               :file_name {:type "string" :description "Filename e.g. recording.mp3"}
                               :file_size {:type "integer" :description "File size in bytes"}
                               :description {:type "string" :description "Episode description (HTML allowed)"}
                               :link {:type "string" :description "Canonical link URL"}
                               :publish_date {:type "string" :description "ISO-8601 UTC datetime to schedule"}
                               :enhance_audio {:type "boolean" :description "Run Audio Enhancement (paid feature)"}}
                  :required ["title" "file_name" "file_size"]}}

   {:name "finalize-upload"
    :description "Finalize a direct upload after PUTing the file to presigned URL."
    :inputSchema {:type "object"
                  :properties {:episode_id {:type "string" :description "Episode UUID from begin-upload"}
                               :blob_name {:type "string" :description "blob_name from begin-upload"}
                               :file_size {:type "integer" :description "File size in bytes"}
                               :use_podhome_ai {:type "boolean" :description "Run Podhome AI (transcript, chapters, clips)"}
                               :suggest_chapters {:type "boolean" :description "Generate chapters via AI"}
                               :suggest_details {:type "boolean" :description "Generate description/title via AI"}
                               :suggest_clips {:type "boolean" :description "Generate soundbite clips via AI"}}
                  :required ["episode_id" "blob_name"]}}

   {:name "upload-and-create"
    :description "Upload a local file and create a draft episode in one operation."
    :inputSchema {:type "object"
                  :properties {:file_path {:type "string" :description "Local path to audio file"}
                               :title {:type "string" :description "Episode title"}
                               :use_podhome_ai {:type "boolean" :description "Run Podhome AI after upload"}}
                  :required ["file_path" "title"]}}

   {:name "delete-episode"
    :description "PERMANENTLY delete an episode. Requires confirm=true."
    :inputSchema {:type "object"
                  :properties {:episode_id {:type "string"}
                               :confirm {:type "boolean" :description "Must be true to execute"}}
                  :required ["episode_id" "confirm"]}}

   ;; Chapters
   {:name "create-chapter"
    :description "Add a chapter to an episode."
    :inputSchema {:type "object"
                  :properties {:episode_id {:type "string" :description "Episode UUID"}
                               :title {:type "string" :description "Chapter title"}
                               :start_time {:type "number" :description "Start time in seconds"}
                               :description {:type "string" :description "Chapter description"}
                               :image_url {:type "string" :description "Chapter image URL"}
                               :url {:type "string" :description "Link for chapter"}}
                  :required ["episode_id" "title" "start_time"]}}

   {:name "modify-chapter"
    :description "Update a chapter."
    :inputSchema {:type "object"
                  :properties {:chapter_id {:type "string" :description "Chapter UUID"}
                               :title {:type "string" :description "Chapter title"}
                               :start_time {:type "number" :description "Start time in seconds"}
                               :description {:type "string" :description "Chapter description"}
                               :image_url {:type "string" :description "Chapter image URL"}
                               :url {:type "string" :description "Link for chapter"}}
                  :required ["chapter_id"]}}

   {:name "delete-chapter"
    :description "Delete a chapter from an episode."
    :inputSchema {:type "object"
                  :properties {:episode_id {:type "string" :description "Episode UUID"}
                               :chapter_id {:type "string" :description "Chapter UUID"}}
                  :required ["episode_id" "chapter_id"]}}

   {:name "get-chapters"
    :description "List chapters for an episode. Paginate with limit/offset."
    :inputSchema {:type "object"
                  :properties {:episode_id {:type "string" :description "Episode UUID"}
                               :limit {:type "integer" :description "Max chapters to return (default 50, max 100)"}
                               :offset {:type "integer" :description "Skip N chapters (for pagination)"}}
                  :required ["episode_id"]}}

   ;; Clips
   {:name "list-clips"
    :description "List clips (soundbites) for an episode. Paginate with limit/offset."
    :inputSchema {:type "object"
                  :properties {:episode_id {:type "string" :description "Episode UUID"}
                               :limit {:type "integer" :description "Max clips to return (default 50, max 100)"}
                               :offset {:type "integer" :description "Skip N clips (for pagination)"}}
                  :required ["episode_id"]}}

   {:name "create-clip"
    :description "Create a clip (soundbite) for an episode."
    :inputSchema {:type "object"
                  :properties {:episode_id {:type "string" :description "Episode UUID"}
                               :title {:type "string" :description "Clip title"}
                               :start_time {:type "number" :description "Start time in seconds"}
                               :duration {:type "number" :description "Duration in seconds"}}
                  :required ["episode_id" "title" "start_time" "duration"]}}

   {:name "modify-clip"
    :description "Update a clip."
    :inputSchema {:type "object"
                  :properties {:clip_id {:type "string" :description "Clip UUID"}
                               :title {:type "string" :description "Clip title"}
                               :start_time {:type "number" :description "Start time in seconds"}
                               :duration {:type "number" :description "Duration in seconds"}}
                  :required ["clip_id"]}}

   {:name "delete-clip"
    :description "Delete a clip."
    :inputSchema {:type "object"
                  :properties {:episode_id {:type "string" :description "Episode UUID"}
                               :clip_id {:type "string" :description "Clip UUID"}}
                  :required ["episode_id" "clip_id"]}}

   ;; Transcript
   {:name "get-transcript"
    :description "Get transcript for an episode. Returns SRT, VTT, or plain text."
    :inputSchema {:type "object"
                  :properties {:episode_id {:type "string" :description "Episode UUID"}}
                  :required ["episode_id"]}}

   ;; Analytics
   {:name "get-analytics"
    :description "Get download analytics. Defaults to last 30 days."
    :inputSchema {:type "object"
                  :properties {:period {:type "string" :description "7d, 14d, 30d, 60d, 90d, 180d, 365d, all"}
                               :episode_id {:type "string" :description "Filter to specific episode"}
                               :dimension {:type "string" :description "Breakdown: apps, countries, devices, os, time_of_day, episodes"}
                               :metric {:type "string" :description "Trend: downloads or audience"}
                               :granularity {:type "string" :description "Trend granularity: days, weeks, months"}}
                  :required []}}])

;; ─── Tool Dispatch ──────────────────────────────────────────────────────────

(defn dispatch-tool [name args]
  (case name
    "get-show" (tool-get-show)
    "list-episodes" (tool-list-episodes args)
    "get-episode" (tool-get-episode args)
    "create-episode" (tool-create-episode args)
    "modify-episode" (tool-modify-episode args)
    "schedule-episode" (tool-schedule-episode args)
    "begin-upload" (tool-begin-upload args)
    "finalize-upload" (tool-finalize-upload args)
    "upload-and-create" (tool-upload-and-create args)
    "delete-episode" (tool-delete-episode args)
    "create-chapter" (tool-create-chapter args)
    "modify-chapter" (tool-modify-chapter args)
    "delete-chapter" (tool-delete-chapter args)
    "get-chapters" (tool-get-chapters args)
    "list-clips" (tool-list-clips args)
    "create-clip" (tool-create-clip args)
    "modify-clip" (tool-modify-clip args)
    "delete-clip" (tool-delete-clip args)
    "get-transcript" (tool-get-transcript args)
    "get-analytics" (tool-get-analytics args)
    (throw (Exception. (str "Unknown tool: " name)))))

;; ─── JSON-RPC Handlers ──────────────────────────────────────────────────────

(defn handle-initialize [id _params]
  {:jsonrpc "2.0"
   :id id
   :result {:protocolVersion protocol-version
            :capabilities {:tools {:listChanged false}}
            :serverInfo server-info}})

(defn handle-tools-list [id _params]
  {:jsonrpc "2.0"
   :id id
   :result {:tools tools}})

(defn handle-tools-call [id params]
  (let [tool-name (get params :name)
        args (get params :arguments)]
    (if (and (some? args) (not (map? args)))
      {:jsonrpc "2.0"
       :id id
       :error {:code -32600
               :message "Invalid Request"
               :data "arguments must be an object"}}
      (let [args (or args {})]
        (log "info" "Tool Call" {:tool tool-name :args (vec (keys args))})
        (try
          (let [start (System/currentTimeMillis)
                result (dispatch-tool tool-name args)
                elapsed (- (System/currentTimeMillis) start)
                content (if (:error result)
                          (let [err-msg (or (:message result) (:error result))]
                            [{:type "text" :text (str "Error: " err-msg)}])
                          [{:type "text"
                            :text (json/generate-string result {:pretty true})}])]
            (log "info" "Tool Result" {:tool tool-name :elapsed_ms elapsed :error (:error result)})
            {:jsonrpc "2.0"
             :id id
             :result {:content content
                      :isError (boolean (:error result))}})
          (catch Exception e
            (log "error" "Tool Exception" {:tool tool-name :error (.getMessage e)})
            {:jsonrpc "2.0"
             :id id
             :result {:content [{:type "text" :text (str "Error: " (.getMessage e))}]
                      :isError true}}))))))

(defn dispatch-rpc [body]
  (let [method (keyword (:method body))
        id (:id body)
        params (:params body)]
    (case method
      :initialize (handle-initialize id params)
      :notifications/initialized nil
      :tools/list (handle-tools-list id params)
      :tools/call (handle-tools-call id params)
      {:jsonrpc "2.0"
       :id id
       :error {:code -32601 :message (str "Method not found: " (:method body))}})))

;; ─── HTTP Server ────────────────────────────────────────────────────────────

(defn json-response [status body]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string body)})

(defn handle-mcp [request]
  (let [body-stream (:body request)
        body-raw (when body-stream (slurp body-stream))
        body (try (when body-raw (json/parse-string body-raw true))
                  (catch Exception _
                    (json-response 400 {:error "Invalid JSON"})))]
    (cond
      (nil? body-stream)
      (json-response 400 {:error "Missing request body"})

      (contains? body :status)
      body

      :else
      (case (:request-method request)
        :post
        (let [session-id (find-header request "mcp-session-id")
              new-session? (= "initialize" (:method body))
              sid (if new-session?
                    (create-session!)
                    session-id)]
          (log "debug" "MCP Request" {:method (:method body) :session-id sid :new-session? new-session?})
          (if (and (not new-session?) (not (valid-session? sid)))
            (do
              (log "warn" "Session rejected" {:sid sid :active-sessions (keys @sessions)})
              (json-response 400 {:error "Invalid or missing Mcp-Session-Id"}))
            (let [response (dispatch-rpc body)]
              (if (nil? response)
                {:status 204
                 :headers (cond-> {"Content-Type" "application/json"}
                            new-session? (assoc "Mcp-Session-Id" sid))
                 :body ""}
                {:status 200
                 :headers (cond-> {"Content-Type" "application/json"}
                            new-session? (assoc "Mcp-Session-Id" sid))
                 :body (json/generate-string response)}))))
        {:status 405 :body "Method Not Allowed"}))))

(defn handler [request]
  (let [uri (:uri request)]
    (cond
      (or (= uri "/mcp") (= uri "/mcp/")) (handle-mcp request)
      (= uri "/health") {:status 200
                         :headers {"Content-Type" "application/json"}
                         :body (json/generate-string {:status "ok" :server "podhome-mcp"})}
      :else {:status 404 :body "Not Found"})))

;; ─── Entry Point ────────────────────────────────────────────────────────────

(defn -main [& _args]
  (let [port (or (some-> (System/getenv "PODHOME_MCP_PORT") Integer/parseInt) 0)
        host (or (System/getenv "PODHOME_MCP_HOST") "127.0.0.1")
        srv (http/run-server
             (fn [req] (handler req))
             {:port port :ip host})
        port (-> srv meta :local-port)]
    (log "info" "Server started" {:port port})
    (println (json/generate-string {:port port :status "ready"}))
    @(promise)))

;; ─── Auto-invoke when run directly (not when loaded via load-file) ─────────

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
