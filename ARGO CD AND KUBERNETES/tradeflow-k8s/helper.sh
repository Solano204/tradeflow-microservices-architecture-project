#!/bin/bash

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

show_menu() {
    echo -e "${BLUE}╔═══════════════════════════════════════════════════════╗${NC}"
    echo -e "${BLUE}║   TradeFlow Kubernetes Helper Script                 ║${NC}"
    echo -e "${BLUE}╚═══════════════════════════════════════════════════════╝${NC}"
    echo ""
    echo "1.  View all pods status"
    echo "2.  View service endpoints"
    echo "3.  Check application health"
    echo "4.  View logs for a service"
    echo "5.  Restart a service"
    echo "6.  Scale a service"
    echo "7.  Port forward to a service"
    echo "8.  Access ArgoCD UI"
    echo "9.  Access Grafana"
    echo "10. Access Zipkin"
    echo "11. Check Kafka topics"
    echo "12. Database connection test"
    echo "13. View resource usage"
    echo "14. Check ArgoCD sync status"
    echo "15. Update ECR/Docker secret"
    echo "16. View all events"
    echo "17. Describe problematic pod"
    echo "0.  Exit"
    echo ""
    read -p "Select an option: " choice
}

view_pods() {
    echo -e "${YELLOW}=== Infrastructure Pods ===${NC}"
    kubectl get pods -n tradeflow-infrastructure
    echo ""
    echo -e "${YELLOW}=== Application Pods ===${NC}"
    kubectl get pods -n tradeflow-production
}

view_services() {
    echo -e "${YELLOW}=== Services ===${NC}"
    kubectl get svc -n tradeflow-production
    echo ""
    kubectl get svc -n tradeflow-infrastructure
}

check_health() {
    echo -e "${YELLOW}=== Checking Service Health ===${NC}"
    
    services=("auth-service:8080" "identity-service:8081" "catalog-service:8082" "inventory-service:8083" "order-service:8084" "payment-service:8085" "fraud-service:8087" "search-service:8089")
    
    for svc in "${services[@]}"; do
        IFS=':' read -r name port <<< "$svc"
        echo -n "Checking $name... "
        
        if [[ "$name" == "auth-service" || "$name" == "payment-service" ]]; then
            health_path="/q/health"
        else
            health_path="/actuator/health"
        fi
        
        if kubectl run -it --rm health-check --image=curlimages/curl --restart=Never -- \
           curl -s -o /dev/null -w "%{http_code}" \
           http://${name}.tradeflow-production.svc.cluster.local:${port}${health_path} 2>/dev/null | grep -q "200"; then
            echo -e "${GREEN}✓ Healthy${NC}"
        else
            echo -e "${RED}✗ Unhealthy or unreachable${NC}"
        fi
    done
}

view_logs() {
    echo "Available services:"
    echo "1. auth-service"
    echo "2. identity-service"
    echo "3. catalog-service"
    echo "4. inventory-service"
    echo "5. order-service"
    echo "6. payment-service"
    echo "7. fraud-service"
    echo "8. search-service"
    read -p "Select service (1-8): " svc_choice
    
    case $svc_choice in
        1) SERVICE="auth-service" ;;
        2) SERVICE="identity-service" ;;
        3) SERVICE="catalog-service" ;;
        4) SERVICE="inventory-service" ;;
        5) SERVICE="order-service" ;;
        6) SERVICE="payment-service" ;;
        7) SERVICE="fraud-service" ;;
        8) SERVICE="search-service" ;;
        *) echo "Invalid choice"; return ;;
    esac
    
    echo -e "${YELLOW}Showing logs for $SERVICE (Ctrl+C to exit)${NC}"
    kubectl logs -f deployment/$SERVICE -n tradeflow-production
}

restart_service() {
    echo "Available services:"
    echo "1. auth-service"
    echo "2. identity-service"
    echo "3. catalog-service"
    echo "4. inventory-service"
    echo "5. order-service"
    echo "6. payment-service"
    echo "7. fraud-service"
    echo "8. search-service"
    read -p "Select service (1-8): " svc_choice
    
    case $svc_choice in
        1) SERVICE="auth-service" ;;
        2) SERVICE="identity-service" ;;
        3) SERVICE="catalog-service" ;;
        4) SERVICE="inventory-service" ;;
        5) SERVICE="order-service" ;;
        6) SERVICE="payment-service" ;;
        7) SERVICE="fraud-service" ;;
        8) SERVICE="search-service" ;;
        *) echo "Invalid choice"; return ;;
    esac
    
    echo -e "${YELLOW}Restarting $SERVICE...${NC}"
    kubectl rollout restart deployment/$SERVICE -n tradeflow-production
    kubectl rollout status deployment/$SERVICE -n tradeflow-production
    echo -e "${GREEN}✓ Service restarted${NC}"
}

scale_service() {
    echo "Available services:"
    echo "1. auth-service"
    echo "2. identity-service"
    echo "3. catalog-service"
    echo "4. inventory-service"
    echo "5. order-service"
    echo "6. payment-service"
    echo "7. fraud-service"
    echo "8. search-service"
    read -p "Select service (1-8): " svc_choice
    
    case $svc_choice in
        1) SERVICE="auth-service" ;;
        2) SERVICE="identity-service" ;;
        3) SERVICE="catalog-service" ;;
        4) SERVICE="inventory-service" ;;
        5) SERVICE="order-service" ;;
        6) SERVICE="payment-service" ;;
        7) SERVICE="fraud-service" ;;
        8) SERVICE="search-service" ;;
        *) echo "Invalid choice"; return ;;
    esac
    
    read -p "Enter number of replicas: " REPLICAS
    
    echo -e "${YELLOW}Scaling $SERVICE to $REPLICAS replicas...${NC}"
    kubectl scale deployment/$SERVICE --replicas=$REPLICAS -n tradeflow-production
    echo -e "${GREEN}✓ Service scaled${NC}"
}

port_forward_service() {
    echo "Available services:"
    echo "1. auth-service (8080)"
    echo "2. identity-service (8081)"
    echo "3. catalog-service (8082)"
    echo "4. inventory-service (8083)"
    echo "5. order-service (8084)"
    echo "6. payment-service (8085)"
    echo "7. fraud-service (8087)"
    echo "8. search-service (8089)"
    read -p "Select service (1-8): " svc_choice
    
    case $svc_choice in
        1) SERVICE="auth-service"; PORT="8080" ;;
        2) SERVICE="identity-service"; PORT="8081" ;;
        3) SERVICE="catalog-service"; PORT="8082" ;;
        4) SERVICE="inventory-service"; PORT="8083" ;;
        5) SERVICE="order-service"; PORT="8084" ;;
        6) SERVICE="payment-service"; PORT="8085" ;;
        7) SERVICE="fraud-service"; PORT="8087" ;;
        8) SERVICE="search-service"; PORT="8089" ;;
        *) echo "Invalid choice"; return ;;
    esac
    
    echo -e "${YELLOW}Port forwarding $SERVICE on localhost:$PORT${NC}"
    echo -e "${GREEN}Access at http://localhost:$PORT${NC}"
    kubectl port-forward svc/$SERVICE $PORT:$PORT -n tradeflow-production
}

access_argocd() {
    echo -e "${YELLOW}Getting ArgoCD admin password...${NC}"
    PASSWORD=$(kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath="{.data.password}" | base64 -d)
    echo -e "${GREEN}ArgoCD Admin Password: $PASSWORD${NC}"
    echo -e "${GREEN}Username: admin${NC}"
    echo -e "${YELLOW}Starting port forward...${NC}"
    echo -e "${GREEN}Access at https://localhost:8080${NC}"
    kubectl port-forward svc/argocd-server -n argocd 8080:443
}

access_grafana() {
    echo -e "${GREEN}Grafana Credentials:${NC}"
    echo -e "${GREEN}  Username: admin${NC}"
    echo -e "${GREEN}  Password: tradeflow123${NC}"
    echo -e "${YELLOW}Starting port forward...${NC}"
    echo -e "${GREEN}Access at http://localhost:3000${NC}"
    kubectl port-forward svc/grafana -n tradeflow-infrastructure 3000:3000
}

access_zipkin() {
    echo -e "${YELLOW}Starting Zipkin port forward...${NC}"
    echo -e "${GREEN}Access at http://localhost:9411${NC}"
    kubectl port-forward svc/zipkin -n tradeflow-infrastructure 9411:9411
}

check_kafka_topics() {
    echo -e "${YELLOW}=== Kafka Topics ===${NC}"
    kubectl exec -it kafka-0 -n tradeflow-infrastructure -- \
        kafka-topics --list --bootstrap-server localhost:29092
}

test_database() {
    echo "Available databases:"
    echo "1. auth-postgres"
    echo "2. identity-postgres"
    echo "3. catalog-postgres"
    echo "4. inventory-postgres"
    echo "5. order-postgres"
    echo "6. payment-postgres"
    echo "7. fraud-postgres"
    read -p "Select database (1-7): " db_choice
    
    case $db_choice in
        1) DB="auth-postgres"; USER="tradeflow_auth"; DBNAME="tradeflow_auth" ;;
        2) DB="identity-postgres"; USER="identity"; DBNAME="identity_db" ;;
        3) DB="catalog-postgres"; USER="catalog"; DBNAME="catalog_db" ;;
        4) DB="inventory-postgres"; USER="inventory"; DBNAME="inventory_db" ;;
        5) DB="order-postgres"; USER="order"; DBNAME="order_db" ;;
        6) DB="payment-postgres"; USER="payment"; DBNAME="payment_db" ;;
        7) DB="fraud-postgres"; USER="fraud"; DBNAME="fraud_db" ;;
        *) echo "Invalid choice"; return ;;
    esac
    
    echo -e "${YELLOW}Testing connection to $DB...${NC}"
    kubectl exec -it ${DB}-0 -n tradeflow-infrastructure -- psql -U $USER -d $DBNAME -c "SELECT 1;"
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✓ Database connection successful${NC}"
    else
        echo -e "${RED}✗ Database connection failed${NC}"
    fi
}

view_resource_usage() {
    echo -e "${YELLOW}=== Node Resources ===${NC}"
    kubectl top nodes
    echo ""
    echo -e "${YELLOW}=== Application Pods ===${NC}"
    kubectl top pods -n tradeflow-production
    echo ""
    echo -e "${YELLOW}=== Infrastructure Pods ===${NC}"
    kubectl top pods -n tradeflow-infrastructure
}

check_argocd_status() {
    echo -e "${YELLOW}=== ArgoCD Applications Status ===${NC}"
    kubectl get applications -n argocd
}

update_image_secret() {
    read -p "Using ECR or Docker Hub? (ecr/dockerhub): " REGISTRY
    
    if [ "$REGISTRY" = "ecr" ]; then
        read -p "Enter AWS Region (default: us-east-1): " AWS_REGION
        AWS_REGION=${AWS_REGION:-us-east-1}
        
        echo -e "${YELLOW}Creating/updating ECR secret...${NC}"
        kubectl create secret docker-registry ecr-pull-secret \
            --docker-server=765288911542.dkr.ecr.${AWS_REGION}.amazonaws.com \
            --docker-username=AWS \
            --docker-password=$(aws ecr get-login-password --region ${AWS_REGION}) \
            --namespace=tradeflow-production \
            --dry-run=client -o yaml | kubectl apply -f -
        echo -e "${GREEN}✓ ECR secret updated${NC}"
    else
        echo -e "${YELLOW}For Docker Hub public images, no secret is needed${NC}"
    fi
}

view_events() {
    echo -e "${YELLOW}=== Recent Events (Production) ===${NC}"
    kubectl get events -n tradeflow-production --sort-by='.lastTimestamp' | tail -20
    echo ""
    echo -e "${YELLOW}=== Recent Events (Infrastructure) ===${NC}"
    kubectl get events -n tradeflow-infrastructure --sort-by='.lastTimestamp' | tail -20
}

describe_pod() {
    read -p "Enter pod name: " POD_NAME
    read -p "Namespace (production/infrastructure): " NS
    
    if [ "$NS" = "production" ]; then
        NAMESPACE="tradeflow-production"
    elif [ "$NS" = "infrastructure" ]; then
        NAMESPACE="tradeflow-infrastructure"
    else
        echo "Invalid namespace"
        return
    fi
    
    kubectl describe pod $POD_NAME -n $NAMESPACE
}

# Main loop
while true; do
    show_menu
    
    case $choice in
        1) view_pods ;;
        2) view_services ;;
        3) check_health ;;
        4) view_logs ;;
        5) restart_service ;;
        6) scale_service ;;
        7) port_forward_service ;;
        8) access_argocd ;;
        9) access_grafana ;;
        10) access_zipkin ;;
        11) check_kafka_topics ;;
        12) test_database ;;
        13) view_resource_usage ;;
        14) check_argocd_status ;;
        15) update_image_secret ;;
        16) view_events ;;
        17) describe_pod ;;
        0) echo "Goodbye!"; exit 0 ;;
        *) echo -e "${RED}Invalid option${NC}" ;;
    esac
    
    echo ""
    read -p "Press Enter to continue..."
    clear
done
