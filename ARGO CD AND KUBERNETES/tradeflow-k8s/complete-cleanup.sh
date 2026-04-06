#!/bin/bash
set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${RED}╔═══════════════════════════════════════════════════════╗${NC}"
echo -e "${RED}║   TradeFlow Complete Cleanup Script                  ║${NC}"
echo -e "${RED}║   WARNING: This will delete EVERYTHING               ║${NC}"
echo -e "${RED}╚═══════════════════════════════════════════════════════╝${NC}"
echo ""

read -p "Are you sure you want to delete ALL TradeFlow resources? (yes/no): " CONFIRM
if [ "$CONFIRM" != "yes" ]; then
    echo "Cleanup cancelled."
    exit 0
fi

echo -e "${YELLOW}Starting complete cleanup...${NC}\n"

# 1. Delete ArgoCD Applications
echo -e "${YELLOW}[1/10] Deleting ArgoCD Applications...${NC}"
kubectl delete applications --all -n argocd --ignore-not-found=true
kubectl delete application tradeflow-gateway -n argocd --ignore-not-found=true
kubectl delete application tradeflow-infrastructure -n argocd --ignore-not-found=true
kubectl delete application auth-service -n argocd --ignore-not-found=true
kubectl delete application identity-service -n argocd --ignore-not-found=true
kubectl delete application catalog-service -n argocd --ignore-not-found=true
kubectl delete application inventory-service -n argocd --ignore-not-found=true
kubectl delete application order-service -n argocd --ignore-not-found=true
kubectl delete application payment-service -n argocd --ignore-not-found=true
kubectl delete application fraud-service -n argocd --ignore-not-found=true
kubectl delete application search-service -n argocd --ignore-not-found=true
echo -e "${GREEN}✓ ArgoCD Applications deleted${NC}\n"

# 2. Delete Gateway and Routes
echo -e "${YELLOW}[2/10] Deleting Gateway and HTTPRoutes...${NC}"
kubectl delete httproute tradeflow-api-routes -n tradeflow-production --ignore-not-found=true
kubectl delete gateway tradeflow-gateway -n tradeflow-production --ignore-not-found=true
echo -e "${GREEN}✓ Gateway and Routes deleted${NC}\n"

# 3. Delete Application Services (Deployments, Services, ConfigMaps, Secrets)
echo -e "${YELLOW}[3/10] Deleting Application Services...${NC}"
for service in auth-service identity-service catalog-service inventory-service order-service payment-service fraud-service search-service; do
    echo "  Deleting $service..."
    kubectl delete deployment $service -n tradeflow-production --ignore-not-found=true
    kubectl delete service $service -n tradeflow-production --ignore-not-found=true
    kubectl delete configmap ${service}-config -n tradeflow-production --ignore-not-found=true
    kubectl delete secret ${service}-secret -n tradeflow-production --ignore-not-found=true
done
echo -e "${GREEN}✓ Application Services deleted${NC}\n"

# 4. Delete Infrastructure - StatefulSets and Services
echo -e "${YELLOW}[4/10] Deleting Infrastructure StatefulSets...${NC}"

# Kafka and Zookeeper
kubectl delete job kafka-topics-init -n tradeflow-infrastructure --ignore-not-found=true
kubectl delete statefulset kafka -n tradeflow-infrastructure --ignore-not-found=true
kubectl delete service kafka -n tradeflow-infrastructure --ignore-not-found=true
kubectl delete statefulset zookeeper -n tradeflow-infrastructure --ignore-not-found=true
kubectl delete service zookeeper -n tradeflow-infrastructure --ignore-not-found=true

# Elasticsearch
kubectl delete statefulset search-elasticsearch -n tradeflow-infrastructure --ignore-not-found=true
kubectl delete service search-elasticsearch -n tradeflow-infrastructure --ignore-not-found=true

# PostgreSQL databases
for db in auth-postgres identity-postgres catalog-postgres inventory-postgres order-postgres payment-postgres fraud-postgres; do
    kubectl delete statefulset $db -n tradeflow-infrastructure --ignore-not-found=true
    kubectl delete service $db -n tradeflow-infrastructure --ignore-not-found=true
    kubectl delete secret ${db}-secret -n tradeflow-infrastructure --ignore-not-found=true
done

# MongoDB instances
for db in identity-mongodb catalog-mongodb payment-mongodb fraud-mongodb; do
    kubectl delete statefulset $db -n tradeflow-infrastructure --ignore-not-found=true
    kubectl delete service $db -n tradeflow-infrastructure --ignore-not-found=true
done

# Redis instances
for redis in auth-redis identity-redis catalog-redis payment-redis fraud-redis search-redis; do
    kubectl delete statefulset $redis -n tradeflow-infrastructure --ignore-not-found=true
    kubectl delete service $redis -n tradeflow-infrastructure --ignore-not-found=true
done

echo -e "${GREEN}✓ Infrastructure StatefulSets deleted${NC}\n"

# 5. Delete Observability Stack
echo -e "${YELLOW}[5/10] Deleting Observability Stack...${NC}"
kubectl delete deployment zipkin -n tradeflow-infrastructure --ignore-not-found=true
kubectl delete service zipkin -n tradeflow-infrastructure --ignore-not-found=true
kubectl delete deployment prometheus -n tradeflow-infrastructure --ignore-not-found=true
kubectl delete service prometheus -n tradeflow-infrastructure --ignore-not-found=true
kubectl delete configmap prometheus-config -n tradeflow-infrastructure --ignore-not-found=true
kubectl delete deployment grafana -n tradeflow-infrastructure --ignore-not-found=true
kubectl delete service grafana -n tradeflow-infrastructure --ignore-not-found=true
echo -e "${GREEN}✓ Observability Stack deleted${NC}\n"

# 6. Delete PersistentVolumeClaims
echo -e "${YELLOW}[6/10] Deleting PersistentVolumeClaims...${NC}"
kubectl delete pvc --all -n tradeflow-infrastructure --ignore-not-found=true
kubectl delete pvc --all -n tradeflow-production --ignore-not-found=true
echo -e "${GREEN}✓ PVCs deleted${NC}\n"

# 7. Delete Namespaces
echo -e "${YELLOW}[7/10] Deleting Namespaces...${NC}"
kubectl delete namespace tradeflow-production --ignore-not-found=true
kubectl delete namespace tradeflow-infrastructure --ignore-not-found=true
echo -e "${GREEN}✓ Namespaces deleted (waiting for termination)${NC}\n"

# 8. Delete Traefik (optional)
echo -e "${YELLOW}[8/10] Cleaning up Traefik...${NC}"
read -p "Do you want to delete Traefik? (y/n): " DELETE_TRAEFIK
if [ "$DELETE_TRAEFIK" = "y" ]; then
    helm uninstall traefik -n traefik --ignore-not-found || true
    kubectl delete namespace traefik --ignore-not-found=true
    echo -e "${GREEN}✓ Traefik deleted${NC}\n"
else
    echo -e "${YELLOW}Keeping Traefik${NC}\n"
fi

# 9. Delete ArgoCD (optional)
echo -e "${YELLOW}[9/10] Cleaning up ArgoCD...${NC}"
read -p "Do you want to delete ArgoCD? (y/n): " DELETE_ARGOCD
if [ "$DELETE_ARGOCD" = "y" ]; then
    kubectl delete namespace argocd --ignore-not-found=true
    echo -e "${GREEN}✓ ArgoCD deleted${NC}\n"
else
    echo -e "${YELLOW}Keeping ArgoCD${NC}\n"
fi

# 10. Wait for namespace termination
echo -e "${YELLOW}[10/10] Waiting for namespaces to fully terminate...${NC}"
echo "This may take a few minutes..."
kubectl wait --for=delete namespace/tradeflow-production --timeout=300s 2>/dev/null || true
kubectl wait --for=delete namespace/tradeflow-infrastructure --timeout=300s 2>/dev/null || true

echo ""
echo -e "${GREEN}╔═══════════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║   Cleanup Complete! ✓                                 ║${NC}"
echo -e "${GREEN}╚═══════════════════════════════════════════════════════╝${NC}"
echo ""
echo -e "${YELLOW}Your cluster is now clean and ready for fresh deployment.${NC}"
echo ""
echo -e "${YELLOW}Next steps:${NC}"
echo "1. Run: chmod +x deploy.sh"
echo "2. Run: ./deploy.sh"
echo ""