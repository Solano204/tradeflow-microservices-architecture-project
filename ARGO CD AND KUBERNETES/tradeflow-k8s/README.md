# TradeFlow Kubernetes Deployment Guide

Complete production-ready Kubernetes deployment for TradeFlow microservices platform with ArgoCD GitOps.

## 🏗️ Architecture Overview

```
TradeFlow Platform on Kubernetes
├── Infrastructure Layer (tradeflow-infrastructure namespace)
│   ├── Kafka (Event Streaming)
│   ├── PostgreSQL (8 instances - one per service)
│   ├── MongoDB (4 instances)
│   ├── Redis (6 instances)
│   ├── Elasticsearch (Search)
│   └── Observability (Zipkin, Prometheus, Grafana)
│
└── Application Layer (tradeflow-production namespace)
    ├── auth-service (Port 8080 - Quarkus)
    ├── identity-service (Port 8081 - Spring Boot)
    ├── catalog-service (Port 8082 - Spring Boot)
    ├── inventory-service (Port 8083 - Spring Boot)
    ├── order-service (Port 8084 - Spring Boot)
    ├── payment-service (Port 8085 - Quarkus)
    ├── fraud-service (Port 8087 - Spring Boot)
    └── search-service (Port 8089 - Spring Boot)
```

## 📋 Prerequisites

### Required Tools
- Kubernetes cluster (v1.24+)
- kubectl (v1.24+)
- ArgoCD (v2.8+)
- Helm (v3.12+)
- AWS CLI (for ECR authentication)

### Cluster Requirements
- **Minimum:** 8 CPU cores, 16GB RAM, 100GB storage
- **Recommended:** 16 CPU cores, 32GB RAM, 200GB storage
- StorageClass supporting dynamic PVC provisioning
- LoadBalancer support (for ingress)

## 🚀 Quick Start Deployment

### Step 1: Prepare ECR Authentication

```bash
# Login to AWS ECR
aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin 765288911542.dkr.ecr.us-east-1.amazonaws.com

# Create ECR secret for Kubernetes
kubectl create secret docker-registry ecr-pull-secret \
  --docker-server=765288911542.dkr.ecr.us-east-1.amazonaws.com \
  --docker-username=AWS \
  --docker-password=$(aws ecr get-login-password --region us-east-1) \
  --namespace=tradeflow-production

# If using Docker Hub images instead (as shown in docker-compose)
# No ECR secret needed, just remove imagePullSecrets from deployments
```

### Step 2: Install ArgoCD

```bash
# Create ArgoCD namespace
kubectl create namespace argocd

# Install ArgoCD
kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml

# Wait for ArgoCD to be ready
kubectl wait --for=condition=available --timeout=300s deployment/argocd-server -n argocd

# Get ArgoCD admin password
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath="{.data.password}" | base64 -d

# Port forward to access ArgoCD UI
kubectl port-forward svc/argocd-server -n argocd 8080:443
# Access at https://localhost:8080
# Username: admin
# Password: (from command above)
```

### Step 3: Install Traefik (Gateway Controller)

```bash
# Add Traefik Helm repository
helm repo add traefik https://traefik.github.io/charts
helm repo update

# Install Traefik with Gateway API support
helm install traefik traefik/traefik \
  --namespace traefik \
  --create-namespace \
  --set experimental.kubernetesGateway.enabled=true \
  --set providers.kubernetesGateway.enabled=true

# Verify Traefik installation
kubectl get pods -n traefik
kubectl get svc -n traefik
```

### Step 4: Create Namespaces

```bash
kubectl apply -f base/00-namespace.yaml
```

### Step 5: Deploy Infrastructure Layer

```bash
# Apply all infrastructure components
kubectl apply -f base/infrastructure/

# Wait for PostgreSQL to be ready
kubectl wait --for=condition=ready pod -l app=auth-postgres -n tradeflow-infrastructure --timeout=300s

# Wait for Kafka to be ready
kubectl wait --for=condition=ready pod -l app=kafka -n tradeflow-infrastructure --timeout=300s

# Check infrastructure status
kubectl get pods -n tradeflow-infrastructure
kubectl get pvc -n tradeflow-infrastructure
```

### Step 6: Deploy Application Services via ArgoCD

```bash
# Apply ArgoCD Applications
kubectl apply -f argocd/infrastructure-app.yaml
kubectl apply -f argocd/services-apps.yaml
kubectl apply -f argocd/gateway-app.yaml

# Check ArgoCD applications
kubectl get applications -n argocd

# Watch deployment progress
watch kubectl get pods -n tradeflow-production
```

### Step 7: Verify Deployment

```bash
# Check all pods are running
kubectl get pods -n tradeflow-production
kubectl get pods -n tradeflow-infrastructure

# Check services
kubectl get svc -n tradeflow-production

# Check gateway
kubectl get gateway -n tradeflow-production
kubectl get httproute -n tradeflow-production

# View logs of a service
kubectl logs -f deployment/auth-service -n tradeflow-production
```

## 🔧 Configuration

### ECR Image Pull Secret (Option 1: Using ECR)

If using ECR images, update the secret:

```bash
# Generate base64 encoded dockerconfigjson
kubectl create secret docker-registry ecr-pull-secret \
  --docker-server=765288911542.dkr.ecr.us-east-1.amazonaws.com \
  --docker-username=AWS \
  --docker-password=$(aws ecr get-login-password --region us-east-1) \
  --namespace=tradeflow-production \
  --dry-run=client -o yaml > ecr-secret.yaml

# Apply the secret
kubectl apply -f ecr-secret.yaml
```

### Using Docker Hub Images (Option 2: Recommended for testing)

The docker-compose files show you're using Docker Hub images (`joshua76i/tradeflow-*`). To use these:

1. Remove `imagePullSecrets` from all deployments, or
2. Create a Docker Hub secret if images are private:

```bash
kubectl create secret docker-registry dockerhub-secret \
  --docker-server=https://index.docker.io/v1/ \
  --docker-username=joshua76i \
  --docker-password=YOUR_DOCKERHUB_PASSWORD \
  --namespace=tradeflow-production
```

### Environment Variables

All environment variables are managed via ConfigMaps and Secrets in:
- `base/services/*/config.yaml` - Service-specific configuration
- `base/infrastructure/` - Infrastructure secrets

To update a configuration:

```bash
# Edit ConfigMap
kubectl edit configmap auth-service-config -n tradeflow-production

# Restart deployment to pick up changes
kubectl rollout restart deployment/auth-service -n tradeflow-production
```

## 🌐 Accessing Services

### Internal Access (within cluster)

Services are accessible via internal DNS:
```
http://auth-service.tradeflow-production.svc.cluster.local:8080
http://inventory-service.tradeflow-production.svc.cluster.local:8083
```

### External Access via Gateway

Add to your `/etc/hosts` or use DNS:
```
<EXTERNAL-IP> api.tradeflow.local tradeflow.local
```

Get external IP:
```bash
kubectl get svc -n traefik
```

Access endpoints:
```
http://api.tradeflow.local/api/auth/*
http://api.tradeflow.local/api/inventory/*
http://api.tradeflow.local/api/orders/*
```

### Observability Dashboards

```bash
# Grafana
kubectl port-forward svc/grafana -n tradeflow-infrastructure 3000:3000
# Access at http://localhost:3000
# User: admin / Password: tradeflow123

# Zipkin (Tracing)
kubectl port-forward svc/zipkin -n tradeflow-infrastructure 9411:9411
# Access at http://localhost:9411

# Prometheus
kubectl port-forward svc/prometheus -n tradeflow-infrastructure 9090:9090
# Access at http://localhost:9090
```

## 📊 Monitoring & Debugging

### View Logs

```bash
# View logs for a service
kubectl logs -f deployment/auth-service -n tradeflow-production

# View logs for infrastructure component
kubectl logs -f statefulset/kafka -n tradeflow-infrastructure

# View all logs from a namespace
kubectl logs -l app=order-service -n tradeflow-production --tail=100
```

### Check Resource Usage

```bash
# Pod resource usage
kubectl top pods -n tradeflow-production
kubectl top pods -n tradeflow-infrastructure

# Node resource usage
kubectl top nodes
```

### Debug Networking

```bash
# Test service connectivity
kubectl run -it --rm debug --image=busybox --restart=Never -- sh
# Inside the pod:
wget -O- http://auth-service.tradeflow-production.svc.cluster.local:8080/q/health

# Check DNS resolution
kubectl run -it --rm debug --image=busybox --restart=Never -- nslookup auth-service.tradeflow-production.svc.cluster.local
```

### Check Kafka Topics

```bash
# List Kafka topics
kubectl exec -it kafka-0 -n tradeflow-infrastructure -- kafka-topics --list --bootstrap-server localhost:29092

# Describe a topic
kubectl exec -it kafka-0 -n tradeflow-infrastructure -- kafka-topics --describe --topic order.events --bootstrap-server localhost:29092
```

## 🔄 GitOps Workflow

### Repository Structure

```
tradeflow-gitops/
├── infrastructure/           # Infrastructure components
│   ├── kafka.yaml
│   ├── postgres.yaml
│   ├── mongodb.yaml
│   ├── redis.yaml
│   ├── elasticsearch.yaml
│   └── observability.yaml
├── services/                 # Application services
│   ├── auth-service/
│   │   ├── config.yaml
│   │   └── deployment.yaml
│   ├── identity-service/
│   └── ...
├── gateway/                  # API Gateway
│   └── gateway.yaml
└── argocd/                   # ArgoCD Applications
    ├── infrastructure-app.yaml
    ├── services-apps.yaml
    └── gateway-app.yaml
```

### Making Changes

1. **Update manifests in Git repository**
2. **Commit and push changes**
3. **ArgoCD auto-syncs** (if automated sync is enabled)
4. **Or manually sync** via ArgoCD UI or CLI

```bash
# Manual sync via CLI
argocd app sync auth-service

# Sync all applications
argocd app sync -l app.kubernetes.io/instance=tradeflow
```

## 🔐 Security Best Practices

### 1. Update Secrets

```bash
# Update database passwords
kubectl create secret generic auth-postgres-secret \
  --from-literal=POSTGRES_PASSWORD=YOUR_SECURE_PASSWORD \
  --namespace=tradeflow-infrastructure \
  --dry-run=client -o yaml | kubectl apply -f -
```

### 2. Enable TLS/HTTPS

```bash
# Install cert-manager for automatic TLS
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.13.0/cert-manager.yaml

# Create Let's Encrypt issuer
# (See cert-manager documentation)
```

### 3. Network Policies

```bash
# Apply network policies to restrict pod-to-pod communication
# (Create network policy manifests as needed)
```

## 📈 Scaling

### Horizontal Pod Autoscaling

```bash
# Create HPA for a service
kubectl autoscale deployment auth-service \
  --cpu-percent=70 \
  --min=2 \
  --max=10 \
  -n tradeflow-production

# Check HPA status
kubectl get hpa -n tradeflow-production
```

### Manual Scaling

```bash
# Scale a deployment
kubectl scale deployment/order-service --replicas=5 -n tradeflow-production

# Scale a StatefulSet (e.g., Kafka)
kubectl scale statefulset/kafka --replicas=3 -n tradeflow-infrastructure
```

## 🧹 Cleanup

### Remove All Resources

```bash
# Delete ArgoCD applications
kubectl delete -f argocd/

# Delete all services
kubectl delete namespace tradeflow-production

# Delete infrastructure
kubectl delete namespace tradeflow-infrastructure

# Delete ArgoCD
kubectl delete namespace argocd

# Delete Traefik
helm uninstall traefik -n traefik
kubectl delete namespace traefik
```

## 📝 Troubleshooting

### Common Issues

**1. Pods stuck in Pending**
```bash
# Check PVC status
kubectl get pvc -n tradeflow-infrastructure

# Check node resources
kubectl describe nodes

# Check events
kubectl get events -n tradeflow-production --sort-by='.lastTimestamp'
```

**2. ImagePullBackOff errors**
```bash
# Verify ECR secret
kubectl get secret ecr-pull-secret -n tradeflow-production

# Test Docker login
docker pull joshua76i/tradeflow-auth-service:1.0.0
```

**3. Services not connecting**
```bash
# Check service endpoints
kubectl get endpoints -n tradeflow-production

# Verify DNS
kubectl run -it --rm debug --image=busybox --restart=Never -- nslookup kafka.tradeflow-infrastructure.svc.cluster.local
```

**4. Database connection issues**
```bash
# Check PostgreSQL pods
kubectl get pods -l app=auth-postgres -n tradeflow-infrastructure

# Check logs
kubectl logs statefulset/auth-postgres -n tradeflow-infrastructure

# Test connection
kubectl exec -it auth-postgres-0 -n tradeflow-infrastructure -- psql -U tradeflow_auth -d tradeflow_auth -c "SELECT 1;"
```

## 🎯 Next Steps

1. **Set up CI/CD Pipeline** - Automate image builds and updates
2. **Configure Backup Strategy** - Regular backups of databases
3. **Implement Disaster Recovery** - Cross-region replication
4. **Security Hardening** - Pod Security Policies, Network Policies
5. **Performance Tuning** - Resource limits, JVM tuning
6. **Monitoring Alerts** - Configure alerting in Grafana

## 📚 Additional Resources

- [ArgoCD Documentation](https://argo-cd.readthedocs.io/)
- [Kubernetes Documentation](https://kubernetes.io/docs/)
- [Traefik Gateway API](https://doc.traefik.io/traefik/routing/providers/kubernetes-gateway/)
- [Spring Boot on Kubernetes](https://spring.io/guides/gs/spring-boot-kubernetes/)
- [Quarkus on Kubernetes](https://quarkus.io/guides/deploying-to-kubernetes)

## 🤝 Support

For issues and questions:
- Check logs: `kubectl logs -f deployment/SERVICE_NAME -n tradeflow-production`
- Review events: `kubectl get events -n tradeflow-production`
- ArgoCD UI: Check sync status and errors
