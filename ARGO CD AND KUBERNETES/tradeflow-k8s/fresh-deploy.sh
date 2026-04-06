#!/bin/bash
set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

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

install_argocd() {
    if helm list -n argocd | grep -q argocd; then
        echo -e "${YELLOW}[2/9] ArgoCD already installed${NC}"
        ARGOCD_PASSWORD=$(kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath="{.data.password}" 2>/dev/null | base64 -d || echo "")
        echo -e "${GREEN}✓ ArgoCD verified${NC}\n"
        return
    fi

    echo -e "${YELLOW}[2/9] Installing ArgoCD via Helm...${NC}"

    helm repo add argo https://argoproj.github.io/argo-helm || true
    helm repo update

    kubectl create namespace argocd --dry-run=client -o yaml | kubectl apply -f -

    helm upgrade --install argocd argo/argo-cd \
        --namespace argocd \
        --set server.insecure=false \
        --wait \
        --timeout 5m

    echo "Waiting for ArgoCD to be ready..."
    kubectl wait --for=condition=available --timeout=300s deployment/argocd-server -n argocd

    ARGOCD_PASSWORD=$(kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath="{.data.password}" | base64 -d 2>/dev/null || echo "admin")

    echo -e "${GREEN}✓ ArgoCD installed successfully${NC}"
    echo -e "${BLUE}  ArgoCD Admin Password: ${ARGOCD_PASSWORD}${NC}"
    echo -e "${BLUE}  Access UI: kubectl port-forward svc/argocd-server -n argocd 8080:443${NC}\n"
}

# Install Traefik
install_traefik() {
    if helm list -n traefik | grep -q traefik; then
        echo -e "${YELLOW}[3/9] Traefik already installed${NC}"
        echo -e "${GREEN}✓ Traefik verified${NC}\n"
        return
    fi

    echo -e "${YELLOW}[3/9] Installing Traefik Gateway...${NC}"

    helm repo add traefik https://traefik.github.io/charts || true
    helm repo update

    helm upgrade --install traefik traefik/traefik \
        --namespace traefik \
        --create-namespace \
        --set experimental.kubernetesGateway.enabled=true \
        --set providers.kubernetesGateway.enabled=true \
        --wait

    echo -e "${GREEN}✓ Traefik installed successfully${NC}\n"
}

# Create namespaces
create_namespaces() {
    echo -e "${YELLOW}[4/9] Creating namespaces...${NC}"

    cat <<EOF | kubectl apply -f -
---
apiVersion: v1
kind: Namespace
metadata:
  name: tradeflow-production
  labels:
    name: tradeflow-production
    environment: production
---
apiVersion: v1
kind: Namespace
metadata:
  name: tradeflow-infrastructure
  labels:
    name: tradeflow-infrastructure
    environment: production
EOF

    echo -e "${GREEN}✓ Namespaces created${NC}\n"
}

# Deploy infrastructure
deploy_infrastructure() {
    echo -e "${YELLOW}[5/9] Deploying infrastructure layer...${NC}"

    echo "  → Deploying Kafka & Zookeeper..."
    kubectl apply -f https://raw.githubusercontent.com/Solano204/tradeflow-k8s-gitops/main/infrastructure/kafka.yaml

    echo "  → Deploying PostgreSQL databases..."
    kubectl apply -f https://raw.githubusercontent.com/Solano204/tradeflow-k8s-gitops/main/infrastructure/postgres.yaml

    echo "  → Deploying MongoDB instances..."
    kubectl apply -f https://raw.githubusercontent.com/Solano204/tradeflow-k8s-gitops/main/infrastructure/mongodb.yaml

    echo "  → Deploying Redis instances..."
    kubectl apply -f https://raw.githubusercontent.com/Solano204/tradeflow-k8s-gitops/main/infrastructure/redis.yaml

    echo "  → Deploying Elasticsearch..."
    kubectl apply -f https://raw.githubusercontent.com/Solano204/tradeflow-k8s-gitops/main/infrastructure/elasticsearch.yaml

    echo "  → Deploying observability stack..."
    kubectl apply -f https://raw.githubusercontent.com/Solano204/tradeflow-k8s-gitops/main/infrastructure/observability.yaml

    echo "  Waiting for infrastructure pods to initialize..."
    sleep 30

    echo -e "${GREEN}✓ Infrastructure layer deployed${NC}\n"
}

# Deploy application services
deploy_services() {
    echo -e "${YELLOW}[6/9] Deploying application services...${NC}"

    for service in auth-service identity-service catalog-service inventory-service order-service payment-service fraud-service search-service; do
        echo "  → Deploying $service..."
        kubectl apply -f https://raw.githubusercontent.com/Solano204/tradeflow-k8s-gitops/main/services/$service/deployment.yaml
        sleep 2
    done

    echo -e "${GREEN}✓ Application services deployed${NC}\n"
}

# Deploy gateway with proper namespace reference
deploy_gateway() {
    echo -e "${YELLOW}[7/9] Deploying API Gateway...${NC}"

    cat <<'EOF' | kubectl apply -f -
---
apiVersion: gateway.networking.k8s.io/v1
kind: Gateway
metadata:
  name: tradeflow-gateway
  namespace: tradeflow-production
spec:
  gatewayClassName: traefik
  listeners:
    - name: http
      protocol: HTTP
      port: 80
      allowedRoutes:
        namespaces:
          from: All
---
apiVersion: gateway.networking.k8s.io/v1
kind: HTTPRoute
metadata:
  name: tradeflow-api-routes
  namespace: tradeflow-production
spec:
  parentRefs:
    - name: tradeflow-gateway
      namespace: tradeflow-production
  hostnames:
    - "api.tradeflow.local"
    - "tradeflow.local"
  rules:
    - matches:
        - path:
            type: PathPrefix
            value: /api/auth
      filters:
        - type: URLRewrite
          urlRewrite:
            path:
              type: ReplacePrefixMatch
              replacePrefixMatch: /
      backendRefs:
        - name: auth-service
          namespace: tradeflow-production
          port: 8080

    - matches:
        - path:
            type: PathPrefix
            value: /api/identity
      filters:
        - type: URLRewrite
          urlRewrite:
            path:
              type: ReplacePrefixMatch
              replacePrefixMatch: /
      backendRefs:
        - name: identity-service
          namespace: tradeflow-production
          port: 8081

    - matches:
        - path:
            type: PathPrefix
            value: /api/catalog
      filters:
        - type: URLRewrite
          urlRewrite:
            path:
              type: ReplacePrefixMatch
              replacePrefixMatch: /
      backendRefs:
        - name: catalog-service
          namespace: tradeflow-production
          port: 8082

    - matches:
        - path:
            type: PathPrefix
            value: /api/inventory
      filters:
        - type: URLRewrite
          urlRewrite:
            path:
              type: ReplacePrefixMatch
              replacePrefixMatch: /
      backendRefs:
        - name: inventory-service
          namespace: tradeflow-production
          port: 8083

    - matches:
        - path:
            type: PathPrefix
            value: /api/orders
      filters:
        - type: URLRewrite
          urlRewrite:
            path:
              type: ReplacePrefixMatch
              replacePrefixMatch: /
      backendRefs:
        - name: order-service
          namespace: tradeflow-production
          port: 8084

    - matches:
        - path:
            type: PathPrefix
            value: /api/payments
      filters:
        - type: URLRewrite
          urlRewrite:
            path:
              type: ReplacePrefixMatch
              replacePrefixMatch: /
      backendRefs:
        - name: payment-service
          namespace: tradeflow-production
          port: 8085

    - matches:
        - path:
            type: PathPrefix
            value: /api/fraud
      filters:
        - type: URLRewrite
          urlRewrite:
            path:
              type: ReplacePrefixMatch
              replacePrefixMatch: /
      backendRefs:
        - name: fraud-service
          namespace: tradeflow-production
          port: 8087

    - matches:
        - path:
            type: PathPrefix
            value: /api/search
      filters:
        - type: URLRewrite
          urlRewrite:
            path:
              type: ReplacePrefixMatch
              replacePrefixMatch: /
      backendRefs:
        - name: search-service
          namespace: tradeflow-production
          port: 8089
EOF

    echo -e "${GREEN}✓ API Gateway deployed${NC}\n"
}

# Configure ArgoCD applications
configure_argocd_apps() {
    echo -e "${YELLOW}[8/9] Configuring ArgoCD GitOps (Optional)...${NC}"

    read -p "Do you want to setup ArgoCD GitOps automation? (y/n): " SETUP_ARGOCD

    if [ "$SETUP_ARGOCD" = "y" ]; then
        echo "Applying ArgoCD applications..."
        kubectl apply -f https://raw.githubusercontent.com/Solano204/tradeflow-k8s-gitops/main/argocd/infrastructure-app.yaml
        kubectl apply -f https://raw.githubusercontent.com/Solano204/tradeflow-k8s-gitops/main/argocd/services-apps.yaml
        kubectl apply -f https://raw.githubusercontent.com/Solano204/tradeflow-k8s-gitops/main/argocd/gateway-app.yaml

        echo -e "${GREEN}✓ ArgoCD applications configured${NC}"
    else
        echo -e "${YELLOW}Skipping ArgoCD GitOps setup${NC}"
    fi

    echo ""
}

# Verify deployment
verify_deployment() {
    echo -e "${YELLOW}[9/9] Verifying deployment...${NC}"

    echo ""
    echo -e "${BLUE}Infrastructure Pods:${NC}"
    kubectl get pods -n tradeflow-infrastructure -o wide

    echo ""
    echo -e "${BLUE}Application Pods:${NC}"
    kubectl get pods -n tradeflow-production -o wide

    echo ""
    echo -e "${BLUE}Gateway Status:${NC}"
    kubectl get gateway -n tradeflow-production
    kubectl get httproute -n tradeflow-production

    echo -e "${GREEN}✓ Verification complete${NC}\n"
}

# Display summary
display_summary() {
    echo ""
    echo -e "${GREEN}╔═══════════════════════════════════════════════════════╗${NC}"
    echo -e "${GREEN}║   TradeFlow Deployment Complete! 🚀                  ║${NC}"
    echo -e "${GREEN}╚═══════════════════════════════════════════════════════╝${NC}"
    echo ""

    TRAEFIK_IP=$(kubectl get svc -n traefik traefik -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || echo "")
    if [ -z "$TRAEFIK_IP" ]; then
        TRAEFIK_IP=$(kubectl get svc -n traefik traefik -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || echo "pending")
    fi

    echo -e "${YELLOW}🌐 Access Points:${NC}"
    echo ""
    if [ "$TRAEFIK_IP" != "pending" ]; then
        echo -e "${GREEN}API Gateway:${NC} http://${TRAEFIK_IP}"
        echo -e "${GREEN}Add to /etc/hosts:${NC} ${TRAEFIK_IP} api.tradeflow.local tradeflow.local"
    else
        echo -e "${YELLOW}LoadBalancer IP pending... Check with: kubectl get svc -n traefik${NC}"
    fi
    echo ""

    if [ -n "$ARGOCD_PASSWORD" ]; then
        echo -e "${GREEN}ArgoCD UI:${NC} kubectl port-forward svc/argocd-server -n argocd 8080:443"
        echo -e "${GREEN}           https://localhost:8080 (admin / ${ARGOCD_PASSWORD})${NC}"
    fi
    echo ""
    echo -e "${GREEN}Grafana:${NC} kubectl port-forward svc/grafana -n tradeflow-infrastructure 3000:3000"
    echo -e "${GREEN}         http://localhost:3000 (admin / tradeflow123)${NC}"
    echo ""
    echo -e "${GREEN}Zipkin:${NC} kubectl port-forward svc/zipkin -n tradeflow-infrastructure 9411:9411"
    echo -e "${GREEN}        http://localhost:9411${NC}"
    echo ""

    echo -e "${YELLOW}📚 Useful Commands:${NC}"
    echo -e "  Monitor pods:     ${GREEN}watch kubectl get pods -n tradeflow-production${NC}"
    echo -e "  View logs:        ${GREEN}kubectl logs -f deployment/auth-service -n tradeflow-production${NC}"
    echo -e "  Check services:   ${GREEN}kubectl get svc -n tradeflow-production${NC}"
    echo -e "  Scale service:    ${GREEN}kubectl scale deployment/order-service --replicas=3 -n tradeflow-production${NC}"
    echo ""
}

# Main execution
main() {
    check_prerequisites
    install_argocd
    install_traefik
    create_namespaces
    deploy_infrastructure
    deploy_services
    deploy_gateway
    configure_argocd_apps
    verify_deployment
    display_summary
}

# Run main function
main