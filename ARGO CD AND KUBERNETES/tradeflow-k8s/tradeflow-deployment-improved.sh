#!/bin/bash
set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

# Configuration
TIMEOUT=300
TRAEFIK_TIMEOUT=600
ARGOCD_TIMEOUT=600

# Logging functions
log_header() {
    echo -e "${GREEN}╔════════════════════════════════════════════════════════╗${NC}"
    echo -e "${GREEN}║  $1${NC}"
    echo -e "${GREEN}╚════════════════════════════════════════════════════════╝${NC}"
    echo ""
}

log_step() {
    echo -e "${YELLOW}[$1/9] $2${NC}"
}

log_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

log_info() {
    echo -e "${BLUE}ℹ $1${NC}"
}

log_error() {
    echo -e "${RED}✗ $1${NC}" >&2
}

log_warn() {
    echo -e "${YELLOW}⚠ $1${NC}"
}

# Trap errors with better context
trap 'log_error "Script failed at line $LINENO"; exit 1' ERR

# ============================================================================
# PREREQUISITE CHECKS
# ============================================================================
check_prerequisites() {
    log_step 1 "Checking prerequisites..."

    local missing_tools=()

    for tool in kubectl helm; do
        if ! command -v $tool &> /dev/null; then
            missing_tools+=("$tool")
        fi
    done

    if [ ${#missing_tools[@]} -gt 0 ]; then
        log_error "Missing required tools: ${missing_tools[*]}"
        exit 1
    fi

    log_success "Prerequisites check passed"
    echo ""
}

# ============================================================================
# CLEANUP FUNCTIONS
# ============================================================================
cleanup_argocd() {
    log_info "Cleaning up previous ArgoCD installation..."

    # Delete the Helm release if it exists
    helm delete argocd -n argocd --ignore-not-found=true 2>/dev/null || true

    # Delete the namespace (which deletes all resources within it)
    kubectl delete namespace argocd --ignore-not-found=true 2>/dev/null || true

    # Delete cluster-scoped resources by pattern
    kubectl get clusterrole,clusterrolebinding -o name 2>/dev/null | grep -i argocd | xargs kubectl delete --ignore-not-found=true 2>/dev/null || true

    # Delete CRDs - these are the trickiest
    kubectl delete crd applications.argoproj.io applicationsets.argoproj.io appprojects.argoproj.io --ignore-not-found=true 2>/dev/null || true

    # Wait for cleanup to complete
    sleep 5
}

# ============================================================================
# ARGOCD INSTALLATION (HELM)
# ============================================================================
install_argocd() {
    log_step 2 "Setting up ArgoCD..."

    # Check if ArgoCD is already properly installed
    if helm list -n argocd 2>/dev/null | grep -q argocd; then
        RELEASE_STATUS=$(helm status argocd -n argocd -o json 2>/dev/null | grep -o '"status":"[^"]*' | cut -d'"' -f4)

        if [ "$RELEASE_STATUS" = "deployed" ]; then
            log_info "ArgoCD already properly installed"

            # Get the admin password
            if ARGOCD_PASSWORD=$(kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath="{.data.password}" 2>/dev/null | base64 -d 2>/dev/null); then
                log_success "ArgoCD verified"
                log_info "Admin password retrieved"
            else
                log_warn "Could not retrieve ArgoCD admin password (may have been rotated)"
            fi
            echo ""
            return 0
        else
            log_warn "ArgoCD Helm release exists but is in $RELEASE_STATUS state - cleaning up..."
            cleanup_argocd
        fi
    fi

    log_info "Installing ArgoCD via Helm..."

    # Add Helm repo
    helm repo add argo https://argoproj.github.io/argo-helm || true
    helm repo update

    # Create namespace
    kubectl create namespace argocd --dry-run=client -o yaml | kubectl apply -f -

    # Install with sensible defaults
    helm install argocd argo/argo-cd \
        --namespace argocd \
        --set server.insecure=false \
        --set redis.enabled=true \
        --wait \
        --timeout ${ARGOCD_TIMEOUT}s \
        || {
            log_error "Helm install failed - cleaning up and retrying..."
            cleanup_argocd
            kubectl create namespace argocd --dry-run=client -o yaml | kubectl apply -f -
            helm install argocd argo/argo-cd \
                --namespace argocd \
                --set server.insecure=false \
                --set redis.enabled=true \
                --wait \
                --timeout ${ARGOCD_TIMEOUT}s
        }

    log_info "Waiting for ArgoCD server to be ready..."
    if ! kubectl wait --for=condition=available --timeout=${TIMEOUT}s deployment/argocd-server -n argocd 2>/dev/null; then
        log_warn "Timeout waiting for ArgoCD - it may still be initializing"
    fi

    # Get admin password
    if ARGOCD_PASSWORD=$(kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath="{.data.password}" 2>/dev/null | base64 -d 2>/dev/null); then
        log_success "ArgoCD installed successfully"
        log_info "Admin credentials - Username: admin"
        log_info "Admin credentials - Password: ${ARGOCD_PASSWORD}"
        log_info "Access UI: kubectl port-forward svc/argocd-server -n argocd 8080:443"
        log_info "Then visit https://localhost:8080 and accept the certificate"
    else
        log_error "ArgoCD installed but could not retrieve admin password"
    fi
    echo ""
}

# ============================================================================
# TRAEFIK INSTALLATION (HELM)
# ============================================================================
install_traefik() {
    log_step 3 "Setting up Traefik Gateway..."

    # Check if already installed
    if helm list -n traefik 2>/dev/null | grep -q traefik; then
        RELEASE_STATUS=$(helm status traefik -n traefik -o json 2>/dev/null | grep -o '"status":"[^"]*' | cut -d'"' -f4)

        if [ "$RELEASE_STATUS" = "deployed" ]; then
            log_success "Traefik already installed"
            echo ""
            return 0
        fi
    fi

    log_info "Installing Traefik via Helm..."

    helm repo add traefik https://traefik.github.io/charts || true
    helm repo update

    helm upgrade --install traefik traefik/traefik \
        --namespace traefik \
        --create-namespace \
        --set experimentalV3=true \
        --wait \
        --timeout ${TRAEFIK_TIMEOUT}s \
        || {
            log_error "Traefik install failed - it may not have completed"
            log_warn "Continuing anyway - you may need to manually review this"
        }

    log_success "Traefik installed"
    echo ""
}

# ============================================================================
# NAMESPACE CREATION
# ============================================================================
create_namespaces() {
    log_step 4 "Creating Kubernetes namespaces..."

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

    log_success "Namespaces created"
    echo ""
}

# ============================================================================
# INFRASTRUCTURE DEPLOYMENT
# ============================================================================
deploy_infrastructure() {
    log_step 5 "Deploying infrastructure layer..."

    local services=("kafka" "postgres" "mongodb" "redis" "elasticsearch")
    local failed_services=()

    for service in "${services[@]}"; do
        echo -e "  ${CYAN}→${NC} Deploying ${service}..."

        if ! kubectl apply -f "https://raw.githubusercontent.com/Solano204/tradeflow-k8s-gitops/main/infrastructure/${service}.yaml" 2>/dev/null; then
            log_warn "Failed to deploy ${service} - it may not exist or the URL is unreachable"
            failed_services+=("$service")
        fi

        sleep 2
    done

    # Deploy observability (less critical)
    echo -e "  ${CYAN}→${NC} Deploying observability stack..."
    if ! kubectl apply -f "https://raw.githubusercontent.com/Solano204/tradeflow-k8s-gitops/main/infrastructure/observability.yaml" 2>/dev/null; then
        log_warn "Observability stack deployment failed - continuing without it"
    fi

    log_info "Waiting for infrastructure pods to initialize..."
    sleep 30

    if [ ${#failed_services[@]} -gt 0 ]; then
        log_warn "Some infrastructure services failed to deploy: ${failed_services[*]}"
        log_info "Check the repository URLs and ensure they're accessible"
    fi

    log_success "Infrastructure layer deployed"
    echo ""
}

# ============================================================================
# APPLICATION SERVICES DEPLOYMENT
# ============================================================================
deploy_services() {
    log_step 6 "Deploying application services..."

    local services=(
        "auth-service"
        "identity-service"
        "catalog-service"
        "inventory-service"
        "order-service"
        "payment-service"
        "fraud-service"
        "search-service"
    )

    local failed_services=()

    for service in "${services[@]}"; do
        echo -e "  ${CYAN}→${NC} Deploying ${service}..."

        if ! kubectl apply -f "https://raw.githubusercontent.com/Solano204/tradeflow-k8s-gitops/main/services/${service}/deployment.yaml" 2>/dev/null; then
            log_warn "Failed to deploy ${service}"
            failed_services+=("$service")
        fi

        sleep 2
    done

    if [ ${#failed_services[@]} -gt 0 ]; then
        log_warn "Some services failed to deploy: ${failed_services[*]}"
    else
        log_success "All application services deployed"
    fi
    echo ""
}

# ============================================================================
# API GATEWAY DEPLOYMENT
# ============================================================================
deploy_gateway() {
    log_step 7 "Deploying API Gateway..."

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

    log_success "API Gateway deployed"
    echo ""
}

# ============================================================================
# ARGOCD GITOPS SETUP (OPTIONAL)
# ============================================================================
configure_argocd_apps() {
    log_step 8 "Configuring ArgoCD GitOps (Optional)..."

    read -p "Do you want to setup ArgoCD GitOps automation? (y/n): " SETUP_ARGOCD

    if [ "$SETUP_ARGOCD" = "y" ] || [ "$SETUP_ARGOCD" = "Y" ]; then
        log_info "Applying ArgoCD applications..."

        local argocd_apps=(
            "infrastructure-app"
            "services-apps"
            "gateway-app"
        )

        for app in "${argocd_apps[@]}"; do
            if ! kubectl apply -f "https://raw.githubusercontent.com/Solano204/tradeflow-k8s-gitops/main/argocd/${app}.yaml" 2>/dev/null; then
                log_warn "Failed to apply ArgoCD app: ${app}"
            fi
        done

        log_success "ArgoCD applications configured"
    else
        log_info "Skipping ArgoCD GitOps setup"
    fi

    echo ""
}

# ============================================================================
# DEPLOYMENT VERIFICATION
# ============================================================================
verify_deployment() {
    log_step 9 "Verifying deployment..."
    echo ""

    log_info "Infrastructure Pods:"
    kubectl get pods -n tradeflow-infrastructure -o wide 2>/dev/null || log_warn "No pods found in tradeflow-infrastructure"
    echo ""

    log_info "Application Pods:"
    kubectl get pods -n tradeflow-production -o wide 2>/dev/null || log_warn "No pods found in tradeflow-production"
    echo ""

    log_info "Gateway Status:"
    kubectl get gateway,httproute -n tradeflow-production 2>/dev/null || log_warn "No gateways found"
    echo ""

    log_success "Verification complete"
    echo ""
}

# ============================================================================
# DEPLOYMENT SUMMARY
# ============================================================================
display_summary() {
    log_header "TradeFlow Deployment Complete! 🚀"

    # Get Traefik LoadBalancer IP
    local TRAEFIK_IP=$(kubectl get svc -n traefik traefik -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null)
    if [ -z "$TRAEFIK_IP" ]; then
        TRAEFIK_IP=$(kubectl get svc -n traefik traefik -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null)
    fi

    if [ -z "$TRAEFIK_IP" ]; then
        TRAEFIK_IP="pending"
    fi

    echo -e "${YELLOW}🌐 Access Points:${NC}"
    echo ""

    if [ "$TRAEFIK_IP" != "pending" ]; then
        echo -e "${GREEN}API Gateway:${NC} http://${TRAEFIK_IP}"
        echo -e "${GREEN}Update /etc/hosts:${NC}"
        echo -e "  ${TRAEFIK_IP} api.tradeflow.local tradeflow.local"
    else
        echo -e "${YELLOW}LoadBalancer IP pending...${NC}"
        echo -e "Check with: ${CYAN}kubectl get svc -n traefik${NC}"
    fi
    echo ""

    echo -e "${YELLOW}📊 Dashboard Access:${NC}"
    echo ""

    echo -e "${GREEN}ArgoCD:${NC}"
    echo -e "  Command: ${CYAN}kubectl port-forward svc/argocd-server -n argocd 8080:443${NC}"
    echo -e "  URL: https://localhost:8080"
    echo -e "  Username: admin"
    echo -e "  Password: See above ↑"
    echo ""

    echo -e "${GREEN}Grafana:${NC}"
    echo -e "  Command: ${CYAN}kubectl port-forward svc/grafana -n tradeflow-infrastructure 3000:3000${NC}"
    echo -e "  URL: http://localhost:3000"
    echo -e "  Credentials: admin / tradeflow123"
    echo ""

    echo -e "${GREEN}Zipkin (Tracing):${NC}"
    echo -e "  Command: ${CYAN}kubectl port-forward svc/zipkin -n tradeflow-infrastructure 9411:9411${NC}"
    echo -e "  URL: http://localhost:9411"
    echo ""

    echo -e "${YELLOW}📚 Useful Commands:${NC}"
    echo ""
    echo -e "  Monitor pods:"
    echo -e "    ${CYAN}watch kubectl get pods -n tradeflow-production${NC}"
    echo ""
    echo -e "  View service logs:"
    echo -e "    ${CYAN}kubectl logs -f deployment/auth-service -n tradeflow-production${NC}"
    echo ""
    echo -e "  List all services:"
    echo -e "    ${CYAN}kubectl get svc -n tradeflow-production${NC}"
    echo ""
    echo -e "  Scale a service:"
    echo -e "    ${CYAN}kubectl scale deployment/order-service --replicas=3 -n tradeflow-production${NC}"
    echo ""
    echo -e "  Describe a pod:"
    echo -e "    ${CYAN}kubectl describe pod <pod-name> -n tradeflow-production${NC}"
    echo ""

    echo -e "${YELLOW}🔧 Troubleshooting:${NC}"
    echo ""
    echo -e "  If infrastructure pods fail to start:"
    echo -e "    1. Check repository URLs are accessible"
    echo -e "    2. Verify cluster has sufficient resources (CPU, memory)"
    echo -e "    3. Review pod events: ${CYAN}kubectl describe pod <name> -n <namespace>${NC}"
    echo ""
    echo -e "  If services don't start:"
    echo -e "    1. Ensure infrastructure is ready (databases, cache)"
    echo -e "    2. Check service logs for configuration errors"
    echo -e "    3. Verify environment variables are set correctly"
    echo ""
}

# ============================================================================
# MAIN EXECUTION
# ============================================================================
main() {
    log_header "TradeFlow Kubernetes Deployment Script"

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