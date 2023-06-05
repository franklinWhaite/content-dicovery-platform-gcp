#!/bin/bash
set -eu
if [ "$#" -ne 2 ] && [ "$#" -ne 3 ]
  then
    echo "Usage : sh create-template.sh <gcp project> <run name> <gcp region>" 
    exit -1
fi

GCP_PROJECT=$1
RUN_NAME=$2

if [ "$#" -eq 2 ] 
  then
    GCP_REGION="us-central1"
  else
    GCP_REGION=$3
fi

DOCKER_SERVICES_IMAGE="gcr.io/${GCP_PROJECT}/${GCP_REGION}/$RUN_NAME-services:latest"

gcloud builds submit --tag $DOCKER_SERVICES_IMAGE