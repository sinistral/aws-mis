
(ns sinistral.aws-mis.cdk-sfn
  (:refer-clojure :exclude [cond cond->])
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [clojure.walk :as walk]
            [familiar.alpha.core :refer [cond cond->]]))

(def account-id
  "012345678901")

(def region
  "za-south-1")

(defmulti resolve-get-att-arn (fn [[_ resource-type]] resource-type))

(defmethod resolve-get-att-arn "AWS::Lambda::Function"
  [[resource-name resource-type]]
  (format "arn:aws:lambda:%s:%s:function:%s" region account-id resource-name))

(defmethod resolve-get-att-arn "AWS::IAM::Role"
  [[resource-name resource-type]]
  (format "arn:aws:iam::%s:role/%s" account-id resource-name))

(defmulti resolve-ref (fn [[_ resource-type]] resource-type))

(defmethod resolve-ref "AWS::Lambda::Function"
  [[resource-name resource-type]]
  (format "arn:aws:lambda:%s:%s:function:%s" region account-id resource-name))

(defmethod resolve-ref "AWS::SQS::Queue"
  [[resource-name resource-type]]
  (format "https://sqs.%s.amazonaws.com/%s/%s" region account-id resource-name))

(defmethod resolve-ref "AWS::StepFunctions::StateMachine"
  [[resource-name resource-type]]
  (format "arn:aws:states:%s:%s:stateMachine:%s" region account-id resource-name))

(defmulti localise-resource (fn [_ [_ resource-properties]] (get resource-properties "Type")))

(defmethod localise-resource :default
  [_ [resource-name resource-properties :as resource]]
  resource)

(defmethod localise-resource "AWS::StepFunctions::StateMachine"
  [resource-type-map [res-key res-def :as res-entry]]
  (walk/prewalk (fn [x]
                  (letfn [(get-att-arn? [x]
                            (and (map? x)
                                 (-> x keys #{["Fn::GetAtt"]})
                                 (-> x vals first second #{"Arn"})))
                          (partition-ref? [x]
                            (and (map? x)
                                 (-> x keys #{["Ref"]})
                                 (-> x vals #{["AWS::Partition"]})))
                          (ref? [x]
                            (and (map? x)
                                 (-> x keys #{["Ref"]})))]
                    (cond [(get-att-arn? x)
                           (resolve-get-att-arn (first (select-keys resource-type-map [(-> x vals ffirst)])))]
                          [(partition-ref? x)
                           "aws"]
                          [(ref? x)
                           (resolve-ref (first (select-keys resource-type-map [(-> x vals first)])))]
                          [:else
                           x])))
                res-entry))

(defn localise
  [t]
  (let [resources         (seq (get t "Resources"))
        resource-type-map (into {} (map (fn [[logical-name properties]]
                                          [logical-name (get properties "Type")])
                                        resources))]
    (merge t {"Resources" (into {} (map (partial localise-resource resource-type-map) resources))})))

(defn- definition-string
  [t sm-id]
  (->> (apply str/join (get-in t ["Resources" sm-id "Properties" "DefinitionString" "Fn::Join"]))
       json/read-str
       (walk/postwalk (fn [x]
                        (if (and (map? x) (contains? x "Comment"))
                          (dissoc x "Comment")
                              ; The embedded comments tend to cause problems with shell quoting when passing the
                              ; definition string to the CLI, so we strip them out.
                          x)))
       json/write-str))

(defn- select-sm
  [t sm-id]
  (letfn [(typeof? [[_ res-def] res-type]
            (boolean (#{res-type} (get res-def "Type"))))]
    (get (into {}
               (filter #(typeof? % "AWS::StepFunctions::StateMachine")
                       (seq (get t "Resources"))))
         sm-id)))

(def ^:private mandatory-opts
  #{:template})

(defn- missing-mandatory?
  [opts]
  (not-every? opts mandatory-opts))

(def ^:private cli-opts
  [[nil "--definition-string"
    "Given the ID (-i) of a Step Functions State Machine, emit the JSON definition string that might be passed to 'aws stepfunctions create-state-machine --definition ...'"]
   ["-h" "--help"]
   ["-i" "--state-machine-id ID"
    "Limit output to the State Machine with the specified logical ID in the template."]
   ["-l" "--localise"
    "Localise the Resource definitions in Step Functions State Machine definitions."]
   ["-t" "--template TEMPLATE"
    "CDK-synthesised template (i.e.: cdk --no-staging synth)"
    :validate [#(.exists (io/as-file %)) "Template file does not exist."]]])

(defn process-template
  ([options]
   (process-template (json/read-str (slurp (:template options))) options))
  ([template options]
   (cond-> template
     [(:localise options)
      localise]
     [(:definition-string options)
      (definition-string (:state-machine-id options))]
     [(and (:state-machine-id options) (not (:definition-string options)))
      (select-sm (:state-machine-id options))]
     [true
      (identity)])))

(defn -main
  [& args]
  (let [{:keys [options arguments summary errors]} (cli/parse-opts args cli-opts)]
    (cond [(:help options)              (println summary)]
          [errors                       (throw (ex-info "Invocation error" {:errors errors}))]
          [(missing-mandatory? options) (throw (ex-info "Invocation error" {:required mandatory-opts}))]
          [:else                        (println (process-template options))])))

(comment
  (def template
    (json/read-str (slurp "/Users/marc/Development/sinistral/dobby/build/service.git/target/dobby-service.json")))
  (def res-def-seq
    (seq (get template "Resources")))
  (def res-type-map
    (into {} (map (fn [[logical-name properties]]
                    [logical-name (get properties "Type")])
                  res-def-seq)))

  (def sm-def
    (-> template
        (get "Resources")
        (select-keys ["BuildEventProcessingStateMachine201911304B29E4ED"])
        seq
        first
        ((partial localise-resource res-type-map))))

  (-> template
      (get "Resources")
      (select-keys ["RegisterPackageS3IntegrationApiGatewayExecutionRole3D8D6388"])
      seq
      first
      ((partial localise-resource res-type-map)))

  )
