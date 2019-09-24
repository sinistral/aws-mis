#!/usr/bin/env sh

localhost=host.docker.internal
# https://docs.docker.com/docker-for-mac/networking/#there-is-no-docker0-bridge-on-macos

usage () {
    echo "Usage: $0 state-machine-logical-cfn-id"
    exit $1
}

set -ex

[[ -z $1 ]] && usage 1
state_machine_id=$1

yaml_cfn_template=$(mktemp -t cfn-yaml)
if [[ -e ./node_modules/cdk/bin/cdk ]]; then
    cdk=./node_modules/cdk/bin/cdk
else
    cdk=cdk
fi
${cdk} synth --no-staging > ${yaml_cfn_template}

local_account=${AWS_ACCOUNT_ID:-123456789012}
local_region=${AWS_DEFAULT_REGION:-eu-central-1}

docker run \
       $(env | grep AWS | cut -d '=' -f 1 | xargs -n 1 echo "-e" | xargs) \
       -e LAMBDA_ENDPOINT=http://${localhost}:3001 \
       -e AWS_ACCOUNT_ID=${local_account} \
       -e AWS_DEFAULT_REGION=${local_region} \
       -p 8083:8083 \
       amazon/aws-stepfunctions-local &

sleep 3s

json_cfn_template=$(mktemp -t $(basename ${yaml_cfn_template}))
python -c 'import sys, yaml, json; json.dump(yaml.load(sys.stdin), sys.stdout, indent=2)' \
       < ${yaml_cfn_template} \
       > ${json_cfn_template}

aws --region eu-central-1 \
    stepfunctions --endpoint http://localhost:8083 \
    create-state-machine \
    --name ${state_machine_id} \
    --role-arn "arn:aws:iam::012345678901:role/DummyRole" \
    --definition $(clojure -Sdeps "{:deps {sinistral/aws-mis.cdk-sfn {:git/url \"git@github.com:sinistral/aws-mis.git\" :deps/root \"cdk-sfn\" :sha \"4be4d230389744f5e04821ab8b5c684176985549\"}}}" \
                           -m sinistral.aws-mis.cdk-sfn -t ${json_cfn_template} \
                           -l \
                           -i \
                           ${state_machine_id} \
                           --definition-string)

sam local start-lambda \
    --port 3001 \
    --region ${local_region} \
    -t ${yaml_cfn_template}
