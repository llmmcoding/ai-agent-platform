#!/bin/bash
# Kubernetes 部署脚本

set -e

NAMESPACE=${NAMESPACE:-ai-agent}
K8S_DIR=${K8S_DIR:-./infra/k8s}

echo "Deploying AI Agent Platform to Kubernetes..."

# 创建命名空间
kubectl create namespace ${NAMESPACE} --dry-run=client -o yaml | kubectl apply -f -

# 部署 ConfigMap
kubectl apply -f ${K8S_DIR}/configmap.yaml -n ${NAMESPACE}

# 部署 Agent Core
kubectl apply -f ${K8S_DIR}/agent-core-deployment.yaml -n ${NAMESPACE}

# 部署 Python Worker
kubectl apply -f ${K8S_DIR}/python-worker-deployment.yaml -n ${NAMESPACE}

# 部署 Service
kubectl apply -f ${K8S_DIR}/service.yaml -n ${NAMESPACE}

# 部署 Ingress
kubectl apply -f ${K8S_DIR}/ingress.yaml -n ${NAMESPACE}

echo "Deployment completed!"
kubectl get all -n ${NAMESPACE}
