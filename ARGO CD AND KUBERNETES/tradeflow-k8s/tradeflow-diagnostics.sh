#!/bin/bash

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}╔═════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║   TradeFlow Kubernetes Diagnostics                     ║${NC}"
echo -e "${BLUE}╚═════════════════════════════════════════════════════════╝${NC}"
echo ""

# 1. Check Infrastructure
echo -e "${YELLOW}[1] Infrastructure Status${NC}"
echo -e "${GREEN}Databases:${NC}"
kubectl get statefulset -n tradeflow-infrastructure -l tier=database -o wide 2>/dev/null || echo "No database statefulsets found"
echo ""

echo -e "${GREEN}Message Queue:${NC}"
kubectl get statefulset kafka -n tradeflow-infrastructure -o wide 2>/dev/null || echo "Kafka not found"
echo ""

echo -e "${GREEN}Cache:${NC}"
kubectl get statefulset -n tradeflow-infrastructure -l tier=cache -o wide 2>/dev/null || echo "No cache statefulsets found"
echo ""

# 2. Check Kafka Initialization
echo -e "${YELLOW}[2] Kafka Initialization Status${NC}"
kubectl get job kafka-topics-init -n tradeflow-infrastructure
echo ""
echo -e "${GREEN}Kafka Topics Init Logs:${NC}"
KAFKA_JOB_POD=$(kubectl get pods -n tradeflow-infrastructure -l job-name=kafka-topics-init -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)
if [ -n "$KAFKA_JOB_POD" ]; then
    kubectl logs "$KAFKA_JOB_POD" -n tradeflow-infrastructure 2>/dev/null | tail -20
else
    echo "No kafka-topics-init pod found"
fi
echo ""

# 3. Check Application Services
echo -e "${YELLOW}[3] Application Services Status${NC}"
echo -e "${GREEN}Deployments:${NC}"
kubectl get deployment -n tradeflow-production -o wide
echo ""

echo -e "${GREEN}Service Errors:${NC}"
for service in auth-service identity-service catalog-service inventory-service order-service payment-service fraud-service search-service; do
    POD=$(kubectl get pods -n tradeflow-production -l app=$service -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)
    if [ -n "$POD" ]; then
        STATUS=$(kubectl get pod "$POD" -n tradeflow-production -o jsonpath='{.status.phase}')
        if [ "$STATUS" != "Running" ]; then
            echo -e "${RED}$service (Pod: $POD):${NC}"
            kubectl describe pod "$POD" -n tradeflow-production | grep -A 5 "Events:"
            echo ""
        fi
    fi
done

# 4. Check Gateway
echo -e "${YELLOW}[4] Gateway Status${NC}"
kubectl get gateway,httproute -n tradeflow-production
echo ""

# 5. Check ArgoCD
echo -e "${YELLOW}[5] ArgoCD Status${NC}"
echo -e "${GREEN}Pods:${NC}"
kubectl get pods -n argocd -o wide
echo ""

# 6. Check Helm Releases
echo -e "${YELLOW}[6] Helm Releases${NC}"
helm list -a -A
echo ""

# 7. Check for ConfigMap/Secret Issues
echo -e "${YELLOW}[7] ConfigMaps & Secrets${NC}"
echo -e "${GREEN}Secrets in tradeflow-production:${NC}"
kubectl get secrets -n tradeflow-production
echo ""

echo -e "${GREEN}ConfigMaps in tradeflow-production:${NC}"
kubectl get configmaps -n tradeflow-production
echo ""

# 8. Check Resource Availability
echo -e "${YELLOW}[8] Cluster Resources${NC}"
echo -e "${GREEN}Node Status:${NC}"
kubectl get nodes -o wide
echo ""

echo -e "${GREEN}Resource Usage:${NC}"
kubectl top nodes 2>/dev/null || echo "Metrics not available"
echo ""

# 9. Storage
echo -e "${YELLOW}[9] Storage Status${NC}"
kubectl get pvc -A 2>/dev/null | grep tradeflow || echo "No PVCs found in TradeFlow namespaces"
echo ""

# 10. Network Policies
echo -e "${YELLOW}[10] Network Connectivity${NC}"
echo -e "${GREEN}Testing auth-service to auth-postgres:${NC}"
AUTH_POD=$(kubectl get pods -n tradeflow-production -l app=auth-service -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)
if [ -n "$AUTH_POD" ]; then
    kubectl exec -it "$AUTH_POD" -n tradeflow-production -- nc -zv auth-postgres 5432 2>/dev/null || echo "Cannot reach auth-postgres (may not be running)"
fi
echo ""

echo -e "${YELLOW}[11] Summary of Issues${NC}"
echo ""

# Check pod statuses
FAILED_PODS=$(kubectl get pods -A --field-selector=status.phase!=Running,status.phase!=Succeeded -o jsonpath='{range .items[*]}{.metadata.namespace}{"\t"}{.metadata.name}{"\t"}{.status.phase}{"\n"}{end}' 2>/dev/null | grep tradeflow)

if [ -n "$FAILED_PODS" ]; then
    echo -e "${RED}Failed/Non-Running Pods:${NC}"
    echo "$FAILED_PODS"
    echo ""
fi

echo -e "${BLUE}Diagnostic complete. For detailed error messages, run:${NC}"
echo ""
echo -e "${GREEN}kubectl describe pod <pod-name> -n <namespace>${NC}"
echo -e "${GREEN}kubectl logs <pod-name> -n <namespace>{{NC}"
echo ""