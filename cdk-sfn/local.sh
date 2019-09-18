#!/usr/bin/env sh

localhost=host.docker.internal
# https://docs.docker.com/docker-for-mac/networking/#there-is-no-docker0-bridge-on-macos

usage () {
    echo "Usage: $0 template-file state-machine-logical-cfn-id"
    exit $1
}

set -ex

[[ -z $1 ]] && usage 1
cfn_template=$1
[[ -z $2 ]] && usage 1
state_machine_id=$2

docker run \
       $(env | grep AWS | cut -d '=' -f 1 | xargs -n 1 echo "-e" | xargs) \
       -e LAMBDA_ENDPOINT=http://${localhost}:3001 \
       -p 8083:8083 \
       amazon/aws-stepfunctions-local &

sleep 3s

json_cfn_template=$(mktemp -t $(basename ${cfn_template}))
python -c 'import sys, yaml, json; json.dump(yaml.load(sys.stdin), sys.stdout, indent=2)' \
       < ${cfn_template} \
       > ${json_cfn_template}

aws --region eu-central-1 \
    stepfunctions --endpoint http://localhost:8083 \
    create-state-machine \
    --name ${state_machine_id} \
    --role-arn "arn:aws:iam::012345678901:role/DummyRole" \
    --definition $(clojure -Sdeps "{:deps {sinistral/aws-mis.cdk-sfn {:local/root \"${DEVROOT}/aws-mis.git/cdk-sfn\"}}}" \
                           -m sinistral.aws-mis.cdk-sfn -t ${json_cfn_template} \
                           -l \
                           -i \
                           ${state_machine_id} \
                           --definition-string)

sam local start-lambda \
    --port 3001 \
    -t ${cfn_template}
