#!/bin/bash
set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${RED}╔═══════════════════════════════════════════════════════╗${NC}"
echo -e "${RED}║   TradeFlow Kubernetes Cleanup Script                ║${NC}"
echo -e "${RED}║   WARNING: This will delete ALL TradeFlow resources  ║${NC}"
echo -e "${RED}╚═══════════════════════════════════════════════════════╝${NC}"
echo ""

read -p "Are you sure you want to delete all TradeFlow resources? (yes/no): " CONFIRM
if [ "$CONFIRM" != "yes" ]; then
    echo "Cleanup cancelled."
    exit 0
fi

echo ""
echo -e "${YELLOW}Starting cleanup process...${NC}"
echo ""

# Delete ArgoCD applications
echo -e "${YELLOW}[1/7] Deleting ArgoCD applications...${NC}"
kubectl delete -f argocd/ --ignore-not-found=true
echo -e "${GREEN}✓ ArgoCD applications deleted${NC}\n"

# Delete Gateway
echo -e "${YELLOW}[2/7] Deleting API Gateway...${NC}"
kubectl delete -f base/gateway.yaml --ignore-not-found=true
echo -e "${GREEN}✓ API Gateway deleted${NC}\n"

# Delete Application Services
echo -e "${YELLOW}[3/7] Deleting application services...${NC}"
kubectl delete -f base/services/ --ignore-not-found=true --recursive
echo -e "${GREEN}✓ Application services deleted${NC}\n"

# Delete Infrastructure
echo -e "${YELLOW}[4/7] Deleting infrastructure components...${NC}"
kubectl delete -f base/infrastructure/ --ignore-not-found=true --recursive
echo -e "${GREEN}✓ Infrastructure components deleted${NC}\n"

# Delete Namespaces
echo -e "${YELLOW}[5/7] Deleting namespaces...${NC}"
kubectl delete namespace tradeflow-production --ignore-not-found=true
kubectl delete namespace tradeflow-infrastructure --ignore-not-found=true
echo -e "${GREEN}✓ Namespaces deleted${NC}\n"

# Optional: Delete ArgoCD
read -p "Do you want to delete ArgoCD? (y/n): " DELETE_ARGOCD
if [ "$DELETE_ARGOCD" = "y" ]; then
    echo -e "${YELLOW}[6/7] Deleting ArgoCD...${NC}"
    kubectl delete namespace argocd --ignore-not-found=true
    echo -e "${GREEN}✓ ArgoCD deleted${NC}\n"
else
    echo -e "${YELLOW}[6/7] Keeping ArgoCD${NC}\n"
fi

# Optional: Delete Traefik
read -p "Do you want to delete Traefik? (y/n): " DELETE_TRAEFIK
if [ "$DELETE_TRAEFIK" = "y" ]; then
    echo -e "${YELLOW}[7/7] Deleting Traefik...${NC}"
    helm uninstall traefik -n traefik --ignore-not-found
    kubectl delete namespace traefik --ignore-not-found=true
    echo -e "${GREEN}✓ Traefik deleted${NC}\n"
else
    echo -e "${YELLOW}[7/7] Keeping Traefik${NC}\n"
fi

# Wait for resources to be deleted
echo -e "${YELLOW}Waiting for resources to be fully deleted...${NC}"
sleep 10

echo ""
echo -e "${GREEN}╔═══════════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║   Cleanup Complete! ✨                                ║${NC}"
echo -e "${GREEN}╚═══════════════════════════════════════════════════════╝${NC}"
echo ""

# Show remaining PVCs (if any)
echo -e "${YELLOW}Checking for remaining PVCs...${NC}"
kubectl get pvc --all-namespaces 2>/dev/null || true
echo ""

echo -e "${YELLOW}Note: Persistent Volumes (PVs) may still exist depending on your StorageClass reclaim policy.${NC}"
echo -e "${YELLOW}To delete them manually: kubectl delete pv --all${NC}"
echo ""
