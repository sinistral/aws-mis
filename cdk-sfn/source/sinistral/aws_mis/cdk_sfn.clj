
(ns sinistral.aws-mis.cdk-sfn
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [clojure.walk :as walk]))

(defn- typeof?
  [[_ res-def] res-type]
  (boolean (#{res-type} (get res-def "Type"))))

(defn xform-sfn
  [[res-key res-def :as res-entry]
   {:keys [functions roles]}]
  (if (typeof? res-entry "AWS::StepFunctions::StateMachine")
    (walk/prewalk (fn [x]
                    (letfn [(get-att-arn? [x targets]
                              (and (map? x)
                                   (-> x keys #{["Fn::GetAtt"]})
                                   (-> x vals first second #{"Arn"})
                                   (-> x vals ffirst targets)))]
                      (cond (get-att-arn? x functions) (str "arn:aws:lambda:us-east-1:012345678901:function:" (-> x vals ffirst))
                            (get-att-arn? x roles)     (str "arn:aws:iam::012345678901:role/" (-> x vals ffirst))
                            :else                      x)))
                  res-entry)
    res-entry))

(defn localise
  [t]
  (let [resources (seq (get t "Resources"))
        functions (into #{} (map first (filter #(typeof? % "AWS::Lambda::Function") resources)))
        roles     (into #{} (map first (filter #(typeof? % "AWS::IAM::Role") resources)))]
    (merge t {"Resources" (into {} (map #(xform-sfn % {:functions functions :roles roles})
                                        resources))})))

(defn- definition-string
  [t sm-id]
  (apply str/join (get-in t ["Resources" sm-id "Properties" "DefinitionString" "Fn::Join"])))

(defn- select-sm
  [t sm-id]
  (get (into {}
             (filter #(typeof? % "AWS::StepFunctions::StateMachine")
                     (seq (get t "Resources"))))
       sm-id))

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
     (:localise options)                      localise
     (:definition-string options)             (definition-string (:state-machine-id options))
     (and (:state-machine-id options)
          (not (:definition-string options))) (select-sm (:state-machine-id options))
     true                                     (identity))))

(defn -main
  [& args]
  (let [{:keys [options arguments summary errors]} (cli/parse-opts args cli-opts)]
    (cond (:help options)              (println summary)
          errors                       (throw (ex-info "Invocation error" {:errors errors}))
          (missing-mandatory? options) (throw (ex-info "Invocation error" {:required mandatory-opts}))
          :else                        (println (process-template options)))))
