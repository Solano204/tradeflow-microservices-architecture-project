#!/bin/bash
set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}╔═══════════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║   TradeFlow Kubernetes Deployment Script             ║${NC}"
echo -e "${GREEN}║   Production-Ready Microservices Platform            ║${NC}"
echo -e "${GREEN}╚═══════════════════════════════════════════════════════╝${NC}"
echo ""






# Check prerequisites
check_prerequisites() {
    echo -e "${YELLOW}[1/9] Checking prerequisites...${NC}"
    
    command -v kubectl >/dev/null 2>&1 || { echo -e "${RED}kubectl is required but not installed. Aborting.${NC}" >&2; exit 1; }
    command -v helm >/dev/null 2>&1 || { echo -e "${RED}helm is required but not installed. Aborting.${NC}" >&2; exit 1; }
    
    echo -e "${GREEN}✓ Prerequisites check passed${NC}\n"
}

# Install ArgoCD
install_argocd() {

  # At the top of install_argocd(), add this check:
  if kubectl get deployment argocd-server -n argocd &>/dev/null; then
      echo -e "${GREEN}✓ ArgoCD already installed, skipping${NC}\n"
      ARGOCD_PASSWORD=$(kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath="{.data.password}" | base64 -d)
      return
  fi

    echo -e "${YELLOW}[2/9] Installing ArgoCD...${NC}"
    
    kubectl create namespace argocd --dry-run=client -o yaml | kubectl apply -f -
    kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml
    
    echo "Waiting for ArgoCD to be ready..."
    kubectl wait --for=condition=available --timeout=300s deployment/argocd-server -n argocd
    
    # Get admin password
    ARGOCD_PASSWORD=$(kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath="{.data.password}" | base64 -d)
    
    echo -e "${GREEN}✓ ArgoCD installed successfully${NC}"
    echo -e "${GREEN}  ArgoCD Admin Password: ${ARGOCD_PASSWORD}${NC}"
    echo -e "${GREEN}  Access UI: kubectl port-forward svc/argocd-server -n argocd 8080:443${NC}\n"
}

# Install Traefik
# Install Traefik
install_traefik() {
    echo -e "${YELLOW}[3/9] Installing Traefik Gateway...${NC}"

    # Skip if already installed
    if kubectl get deployment traefik -n traefik &>/dev/null; then
        echo -e "${GREEN}✓ Traefik already installed, skipping${NC}\n"
        return
    fi

    helm repo add traefik https://traefik.github.io/charts
    helm repo update

    # Force-update the Gateway API CRDs first to avoid conflicts
    kubectl apply --force-conflicts --server-side \
        -f https://github.com/kubernetes-sigs/gateway-api/releases/download/v1.0.0/standard-install.yaml

    helm upgrade --install traefik traefik/traefik \
        --namespace traefik \
        --create-namespace \
        --set experimental.kubernetesGateway.enabled=true \
        --set providers.kubernetesGateway.enabled=true \
        --set ports.web.exposedPort=80 \
        --set service.type=LoadBalancer \
        --skip-crds \
        --wait

    echo -e "${GREEN}✓ Traefik installed successfully${NC}\n"
}

# Create namespaces
create_namespaces() {
    echo -e "${YELLOW}[4/9] Creating namespaces...${NC}"
    
    kubectl apply -f base/00-namespace.yaml
    
    echo -e "${GREEN}✓ Namespaces created${NC}\n"
}

# Create ECR/Docker secret
create_image_pull_secret() {
    echo -e "${YELLOW}[5/9] Setting up image pull secrets...${NC}"
    
    read -p "Are you using ECR or Docker Hub? (ecr/dockerhub): " IMAGE_REGISTRY
    
    if [ "$IMAGE_REGISTRY" = "ecr" ]; then
        echo "Creating ECR pull secret..."
        read -p "Enter AWS Region (default: us-east-1): " AWS_REGION
        AWS_REGION=${AWS_REGION:-us-east-1}
        
        kubectl create secret docker-registry ecr-pull-secret \
            --docker-server=765288911542.dkr.ecr.${AWS_REGION}.amazonaws.com \
            --docker-username=AWS \
            --docker-password=$(aws ecr get-login-password --region ${AWS_REGION}) \
            --namespace=tradeflow-production \
            --dry-run=client -o yaml | kubectl apply -f -
    elif [ "$IMAGE_REGISTRY" = "dockerhub" ]; then
        echo "Using Docker Hub public images - no secret needed"
        # If using public Docker Hub images, we can skip this or use existing secret
        kubectl apply -f base/01-ecr-secret.yaml 2>/dev/null || true
    else
        echo -e "${YELLOW}Skipping image pull secret creation${NC}"
    fi
    
    echo -e "${GREEN}✓ Image pull secret configured${NC}\n"
}

# Deploy infrastructure
deploy_infrastructure() {
    echo -e "${YELLOW}[6/9] Deploying infrastructure layer...${NC}"
    
    echo "Deploying Kafka..."
    kubectl apply -f base/infrastructure/kafka.yaml
    
    echo "Deploying PostgreSQL databases..."
    kubectl apply -f base/infrastructure/postgres.yaml
    
    echo "Deploying MongoDB instances..."
    kubectl apply -f base/infrastructure/mongodb.yaml
    
    echo "Deploying Redis instances..."
    kubectl apply -f base/infrastructure/redis.yaml
    
    echo "Deploying Elasticsearch..."
    kubectl apply -f base/infrastructure/elasticsearch.yaml
    
    echo "Deploying observability stack..."
    kubectl apply -f base/infrastructure/observability.yaml
    
    echo "Waiting for infrastructure pods to be ready (this may take a few minutes)..."
    sleep 30
    
    echo -e "${GREEN}✓ Infrastructure layer deployed${NC}\n"
}

# Deploy application services
deploy_services() {
    echo -e "${YELLOW}[7/9] Deploying application services...${NC}"
    
    echo "Deploying Auth Service..."
    kubectl apply -f base/services/auth-service/
    
    echo "Deploying Identity Service..."
    kubectl apply -f base/services/identity-service/
    
    echo "Deploying Catalog Service..."
    kubectl apply -f base/services/catalog-service/
    
    echo "Deploying Inventory Service..."
    kubectl apply -f base/services/inventory-service/
    
    echo "Deploying Order Service..."
    kubectl apply -f base/services/order-service/
    
    echo "Deploying Payment Service..."
    kubectl apply -f base/services/payment-service/
    
    echo "Deploying Fraud Service..."
    kubectl apply -f base/services/fraud-service/
    
    echo "Deploying Search Service..."
    kubectl apply -f base/services/search-service/
    
    echo -e "${GREEN}✓ Application services deployed${NC}\n"
}

# Deploy gateway
deploy_gateway() {
    echo -e "${YELLOW}[8/9] Deploying API Gateway...${NC}"
    
    kubectl apply -f base/gateway.yaml
    
    echo -e "${GREEN}✓ API Gateway deployed${NC}\n"
}

# Configure ArgoCD applications
configure_argocd_apps() {
    echo -e "${YELLOW}[9/9] Configuring ArgoCD GitOps...${NC}"
    
    read -p "Do you want to setup ArgoCD GitOps automation? (y/n): " SETUP_ARGOCD
    
    if [ "$SETUP_ARGOCD" = "y" ]; then
        echo "Applying ArgoCD applications..."
        kubectl apply -f argocd/infrastructure-app.yaml
        kubectl apply -f argocd/services-apps.yaml
        kubectl apply -f argocd/gateway-app.yaml
        
        echo -e "${GREEN}✓ ArgoCD applications configured${NC}"
        echo -e "${YELLOW}Note: Make sure to push these manifests to your Git repository:${NC}"
        echo -e "${YELLOW}      https://github.com/Solano204/tradeflow-gitops.git${NC}"
    else
        echo -e "${YELLOW}Skipping ArgoCD GitOps setup${NC}"
    fi
    
    echo ""
}

# Display summary
display_summary() {
    echo -e "${GREEN}╔═══════════════════════════════════════════════════════╗${NC}"
    echo -e "${GREEN}║   TradeFlow Deployment Complete! 🚀                  ║${NC}"
    echo -e "${GREEN}╚═══════════════════════════════════════════════════════╝${NC}"
    echo ""
    
    echo -e "${YELLOW}📊 Deployment Summary:${NC}"
    echo ""
    
    echo -e "${YELLOW}Infrastructure Namespace:${NC}"
    kubectl get pods -n tradeflow-infrastructure
    echo ""
    
    echo -e "${YELLOW}Application Namespace:${NC}"
    kubectl get pods -n tradeflow-production
    echo ""
    
    echo -e "${YELLOW}🌐 Access Points:${NC}"
    echo ""
    
    # Get Traefik LoadBalancer IP
    TRAEFIK_IP=$(kubectl get svc -n traefik traefik -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null)
    if [ -z "$TRAEFIK_IP" ]; then
        TRAEFIK_IP=$(kubectl get svc -n traefik traefik -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null)
    fi
    
    if [ -n "$TRAEFIK_IP" ]; then
        echo -e "${GREEN}API Gateway:${NC} http://${TRAEFIK_IP}"
        echo -e "${GREEN}Add to /etc/hosts:${NC} ${TRAEFIK_IP} api.tradeflow.local tradeflow.local"
    else
        echo -e "${YELLOW}LoadBalancer IP pending... Check with: kubectl get svc -n traefik${NC}"
    fi
    echo ""
    
    echo -e "${GREEN}ArgoCD UI:${NC} kubectl port-forward svc/argocd-server -n argocd 8080:443"
    echo -e "${GREEN}           https://localhost:8080 (admin / ${ARGOCD_PASSWORD})${NC}"
    echo ""
    
    echo -e "${GREEN}Grafana:${NC} kubectl port-forward svc/grafana -n tradeflow-infrastructure 3000:3000"
    echo -e "${GREEN}         http://localhost:3000 (admin / tradeflow123)${NC}"
    echo ""
    
    echo -e "${GREEN}Zipkin:${NC} kubectl port-forward svc/zipkin -n tradeflow-infrastructure 9411:9411"
    echo -e "${GREEN}        http://localhost:9411${NC}"
    echo ""
    
    echo -e "${YELLOW}📚 Useful Commands:${NC}"
    echo -e "  View logs:       ${GREEN}kubectl logs -f deployment/auth-service -n tradeflow-production${NC}"
    echo -e "  Check pods:      ${GREEN}kubectl get pods -n tradeflow-production${NC}"
    echo -e "  Scale service:   ${GREEN}kubectl scale deployment/order-service --replicas=5 -n tradeflow-production${NC}"
    echo -e "  ArgoCD sync:     ${GREEN}kubectl get applications -n argocd${NC}"
    echo ""
    
    echo -e "${YELLOW}📖 Full documentation: README.md${NC}"
    echo ""
}

# Main execution
main() {
    check_prerequisites
    install_argocd
    install_traefik
    create_namespaces
    create_image_pull_secret
    deploy_infrastructure
    deploy_services
    deploy_gateway
    configure_argocd_apps
    display_summary
}

# Run main function
main
