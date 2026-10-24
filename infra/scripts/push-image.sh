#!/usr/bin/env bash
# Builds the image and pushes it to the ECR repository created by infra/aws. Run once before the first full
# apply (the ECS service needs an image to start), or any time you want to push by hand.
#
#   infra/scripts/push-image.sh <ecr-repository-url> [tag]
#   e.g. infra/scripts/push-image.sh "$(tofu -chdir=infra/aws output -raw ecr_repository_url)" bootstrap
#
# Needs: docker and the AWS CLI, logged in to the AWS account (any way you like, for example SSO).
set -euo pipefail

REPO_URL=${1:?usage: push-image.sh <ecr-repository-url> [tag]}
TAG=${2:-bootstrap}
REGISTRY=${REPO_URL%%/*}
REGION=$(cut -d. -f4 <<<"$REGISTRY")

cd "$(dirname "$0")/../.."
aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "$REGISTRY"
docker build -t "$REPO_URL:$TAG" .
docker push "$REPO_URL:$TAG"
echo "pushed $REPO_URL:$TAG"
