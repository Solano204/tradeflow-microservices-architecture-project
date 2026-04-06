# TradeFlow Kubernetes Quick Reference

## 🚀 Quick Start

```bash
# Full deployment
./deploy.sh

# Access ArgoCD UI
kubectl port-forward svc/argocd-server -n argocd 8080:443
# https://localhost:8080

# Get ArgoCD password
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath="{.data.password}" | base64 -d
```

## 📊 Common Commands

### View Resources
```bash
# All pods
kubectl get pods -n tradeflow-production
kubectl get pods -n tradeflow-infrastructure

# All services
kubectl get svc -n tradeflow-production

# All deployments
kubectl get deployments -n tradeflow-production

# All StatefulSets
kubectl get statefulsets -n tradeflow-infrastructure

# All PVCs
kubectl get pvc -n tradeflow-infrastructure
```

### Logs & Debugging
```bash
# View logs (follow)
kubectl logs -f deployment/auth-service -n tradeflow-production

# View logs (last 100 lines)
kubectl logs --tail=100 deployment/order-service -n tradeflow-production

# All logs from a label
kubectl logs -l app=payment-service -n tradeflow-production

# Describe pod
kubectl describe pod <pod-name> -n tradeflow-production

# Execute command in pod
kubectl exec -it <pod-name> -n tradeflow-production -- /bin/bash

# View events
kubectl get events -n tradeflow-production --sort-by='.lastTimestamp'
```

### Service Operations
```bash
# Restart deployment
kubectl rollout restart deployment/auth-service -n tradeflow-production

# Scale deployment
kubectl scale deployment/order-service --replicas=5 -n tradeflow-production

# Rollback deployment
kubectl rollout undo deployment/payment-service -n tradeflow-production

# Check rollout status
kubectl rollout status deployment/inventory-service -n tradeflow-production

# View rollout history
kubectl rollout history deployment/catalog-service -n tradeflow-production
```

### Port Forwarding
```bash
# Forward to service
kubectl port-forward svc/auth-service 8080:8080 -n tradeflow-production

# Forward to pod
kubectl port-forward <pod-name> 8080:8080 -n tradeflow-production

# Grafana
kubectl port-forward svc/grafana 3000:3000 -n tradeflow-infrastructure

# Zipkin
kubectl port-forward svc/zipkin 9411:9411 -n tradeflow-infrastructure

# Prometheus
kubectl port-forward svc/prometheus 9090:9090 -n tradeflow-infrastructure
```

## 🔧 Configuration Updates

### Update ConfigMap
```bash
# Edit
kubectl edit configmap auth-service-config -n tradeflow-production

# From file
kubectl create configmap auth-service-config \
  --from-file=config.yaml \
  --namespace=tradeflow-production \
  --dry-run=client -o yaml | kubectl apply -f -

# Restart to pick up changes
kubectl rollout restart deployment/auth-service -n tradeflow-production
```

### Update Secret
```bash
# Edit
kubectl edit secret auth-service-secret -n tradeflow-production

# Create/update
kubectl create secret generic auth-service-secret \
  --from-literal=DB_PASSWORD=newpassword \
  --namespace=tradeflow-production \
  --dry-run=client -o yaml | kubectl apply -f -
```

### Update Image
```bash
# Set new image
kubectl set image deployment/auth-service \
  auth-service=joshua76i/tradeflow-auth-service:1.1.0 \
  -n tradeflow-production

# Or edit deployment
kubectl edit deployment auth-service -n tradeflow-production
```

## 🗄️ Database Operations

### PostgreSQL
```bash
# Connect to database
kubectl exec -it auth-postgres-0 -n tradeflow-infrastructure -- \
  psql -U tradeflow_auth -d tradeflow_auth

# Run query
kubectl exec -it auth-postgres-0 -n tradeflow-infrastructure -- \
  psql -U tradeflow_auth -d tradeflow_auth -c "SELECT COUNT(*) FROM users;"

# Backup database
kubectl exec auth-postgres-0 -n tradeflow-infrastructure -- \
  pg_dump -U tradeflow_auth tradeflow_auth > backup.sql

# Restore database
kubectl exec -i auth-postgres-0 -n tradeflow-infrastructure -- \
  psql -U tradeflow_auth -d tradeflow_auth < backup.sql
```

### MongoDB
```bash
# Connect to MongoDB
kubectl exec -it identity-mongodb-0 -n tradeflow-infrastructure -- mongosh

# Run command
kubectl exec identity-mongodb-0 -n tradeflow-infrastructure -- \
  mongosh --eval "db.adminCommand('ping')"

# Backup
kubectl exec identity-mongodb-0 -n tradeflow-infrastructure -- \
  mongodump --out=/tmp/backup

# List databases
kubectl exec identity-mongodb-0 -n tradeflow-infrastructure -- \
  mongosh --eval "show dbs"
```

### Redis
```bash
# Connect to Redis
kubectl exec -it auth-redis-0 -n tradeflow-infrastructure -- redis-cli

# Check keys
kubectl exec auth-redis-0 -n tradeflow-infrastructure -- \
  redis-cli KEYS "*"

# Get info
kubectl exec auth-redis-0 -n tradeflow-infrastructure -- \
  redis-cli INFO
```

## 📨 Kafka Operations

### List Topics
```bash
kubectl exec kafka-0 -n tradeflow-infrastructure -- \
  kafka-topics --list --bootstrap-server localhost:29092
```

### Describe Topic
```bash
kubectl exec kafka-0 -n tradeflow-infrastructure -- \
  kafka-topics --describe \
  --topic order.events \
  --bootstrap-server localhost:29092
```

### Consume Messages
```bash
kubectl exec -it kafka-0 -n tradeflow-infrastructure -- \
  kafka-console-consumer \
  --bootstrap-server localhost:29092 \
  --topic order.events \
  --from-beginning
```

### Produce Message
```bash
kubectl exec -it kafka-0 -n tradeflow-infrastructure -- \
  kafka-console-producer \
  --bootstrap-server localhost:29092 \
  --topic order.events
```

## 🔍 Monitoring

### Resource Usage
```bash
# Node resources
kubectl top nodes

# Pod resources (production)
kubectl top pods -n tradeflow-production

# Pod resources (infrastructure)
kubectl top pods -n tradeflow-infrastructure

# Specific pod
kubectl top pod <pod-name> -n tradeflow-production
```

### Health Checks
```bash
# Check service health
kubectl run -it --rm debug --image=curlimages/curl --restart=Never -- \
  curl http://auth-service.tradeflow-production.svc.cluster.local:8080/q/health

# Spring Boot actuator
kubectl run -it --rm debug --image=curlimages/curl --restart=Never -- \
  curl http://order-service.tradeflow-production.svc.cluster.local:8084/actuator/health
```

## 🌐 Gateway & Networking

### Check Gateway
```bash
# View gateway
kubectl get gateway -n tradeflow-production

# View routes
kubectl get httproute -n tradeflow-production

# Describe route
kubectl describe httproute tradeflow-api-routes -n tradeflow-production
```

### Get External IP
```bash
# Traefik LoadBalancer IP
kubectl get svc -n traefik traefik

# Add to /etc/hosts
<EXTERNAL-IP> api.tradeflow.local tradeflow.local
```

### Test Routes
```bash
# From outside cluster (if DNS configured)
curl http://api.tradeflow.local/api/auth/health

# From inside cluster
kubectl run -it --rm debug --image=curlimages/curl --restart=Never -- \
  curl http://api.tradeflow.local/api/orders/health
```

## 🔐 Security

### Update ECR Secret
```bash
kubectl create secret docker-registry ecr-pull-secret \
  --docker-server=765288911542.dkr.ecr.us-east-1.amazonaws.com \
  --docker-username=AWS \
  --docker-password=$(aws ecr get-login-password --region us-east-1) \
  --namespace=tradeflow-production \
  --dry-run=client -o yaml | kubectl apply -f -
```

### View Secret
```bash
# Get secret (base64 encoded)
kubectl get secret auth-service-secret -n tradeflow-production -o yaml

# Decode secret value
kubectl get secret auth-service-secret -n tradeflow-production \
  -o jsonpath='{.data.DB_PASSWORD}' | base64 -d
```

## 🔄 ArgoCD Operations

### View Applications
```bash
# List all applications
kubectl get applications -n argocd

# Describe application
kubectl describe application auth-service -n argocd
```

### Sync Application
```bash
# Via kubectl
kubectl patch application auth-service -n argocd \
  --type merge -p '{"operation":{"initiatedBy":{"username":"admin"},"sync":{}}}'

# Via ArgoCD CLI (if installed)
argocd app sync auth-service
argocd app sync --all
```

### Application Status
```bash
# Get sync status
kubectl get application auth-service -n argocd \
  -o jsonpath='{.status.sync.status}'

# Get health status
kubectl get application auth-service -n argocd \
  -o jsonpath='{.status.health.status}'
```

## 📈 Autoscaling

### Create HPA
```bash
kubectl autoscale deployment auth-service \
  --cpu-percent=70 \
  --min=2 \
  --max=10 \
  -n tradeflow-production
```

### View HPA
```bash
kubectl get hpa -n tradeflow-production
kubectl describe hpa auth-service -n tradeflow-production
```

## 🧹 Cleanup

### Delete Specific Service
```bash
kubectl delete deployment auth-service -n tradeflow-production
kubectl delete svc auth-service -n tradeflow-production
kubectl delete configmap auth-service-config -n tradeflow-production
kubectl delete secret auth-service-secret -n tradeflow-production
```

### Delete All Services
```bash
kubectl delete namespace tradeflow-production
```

### Full Cleanup
```bash
./cleanup.sh
```

## 🐛 Troubleshooting

### Pod Stuck in Pending
```bash
# Check events
kubectl describe pod <pod-name> -n tradeflow-production

# Check PVC
kubectl get pvc -n tradeflow-infrastructure

# Check node resources
kubectl describe nodes
```

### Pod Stuck in ImagePullBackOff
```bash
# Check image pull secret
kubectl get secret ecr-pull-secret -n tradeflow-production

# Describe pod
kubectl describe pod <pod-name> -n tradeflow-production

# Check image exists
docker pull joshua76i/tradeflow-auth-service:1.0.0
```

### CrashLoopBackOff
```bash
# View logs
kubectl logs <pod-name> -n tradeflow-production

# View previous logs
kubectl logs <pod-name> -n tradeflow-production --previous

# Describe pod
kubectl describe pod <pod-name> -n tradeflow-production
```

### Service Not Accessible
```bash
# Check service
kubectl get svc -n tradeflow-production

# Check endpoints
kubectl get endpoints auth-service -n tradeflow-production

# Check pod labels
kubectl get pods --show-labels -n tradeflow-production

# Test from debug pod
kubectl run -it --rm debug --image=nicolaka/netshoot --restart=Never -- bash
# Inside pod:
curl http://auth-service.tradeflow-production.svc.cluster.local:8080/q/health
```

### DNS Issues
```bash
# Test DNS resolution
kubectl run -it --rm debug --image=busybox --restart=Never -- \
  nslookup auth-service.tradeflow-production.svc.cluster.local

# Check CoreDNS
kubectl get pods -n kube-system -l k8s-app=kube-dns
kubectl logs -n kube-system -l k8s-app=kube-dns
```

## 📋 Useful Aliases

Add to your `.bashrc` or `.zshrc`:

```bash
alias k=kubectl
alias kgp='kubectl get pods'
alias kgs='kubectl get svc'
alias kgd='kubectl get deployments'
alias kdp='kubectl describe pod'
alias kl='kubectl logs -f'
alias kex='kubectl exec -it'
alias ktp='kubectl top pods'
alias ktn='kubectl top nodes'

# TradeFlow specific
alias kprod='kubectl get pods -n tradeflow-production'
alias kinfra='kubectl get pods -n tradeflow-infrastructure'
alias klogs='kubectl logs -f -n tradeflow-production'
```

## 🎯 Service Endpoints

### Internal (Cluster)
- Auth: `http://auth-service.tradeflow-production.svc.cluster.local:8080`
- Identity: `http://identity-service.tradeflow-production.svc.cluster.local:8081`
- Catalog: `http://catalog-service.tradeflow-production.svc.cluster.local:8082`
- Inventory: `http://inventory-service.tradeflow-production.svc.cluster.local:8083`
- Order: `http://order-service.tradeflow-production.svc.cluster.local:8084`
- Payment: `http://payment-service.tradeflow-production.svc.cluster.local:8085`
- Fraud: `http://fraud-service.tradeflow-production.svc.cluster.local:8087`
- Search: `http://search-service.tradeflow-production.svc.cluster.local:8089`

### External (via Gateway)
- Auth: `http://api.tradeflow.local/api/auth/*`
- Identity: `http://api.tradeflow.local/api/identity/*`
- Catalog: `http://api.tradeflow.local/api/catalog/*`
- Inventory: `http://api.tradeflow.local/api/inventory/*`
- Orders: `http://api.tradeflow.local/api/orders/*`
- Payments: `http://api.tradeflow.local/api/payments/*`
- Fraud: `http://api.tradeflow.local/api/fraud/*`
- Search: `http://api.tradeflow.local/api/search/*`

## 🔗 Helpful Links

- Kubernetes Docs: https://kubernetes.io/docs/
- ArgoCD Docs: https://argo-cd.readthedocs.io/
- Traefik Docs: https://doc.traefik.io/traefik/
- Helm Docs: https://helm.sh/docs/
