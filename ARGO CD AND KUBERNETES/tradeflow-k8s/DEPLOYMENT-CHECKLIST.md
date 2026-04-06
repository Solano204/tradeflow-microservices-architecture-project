# TradeFlow Kubernetes Deployment Checklist

## Pre-Deployment Checklist

### ✅ Infrastructure Readiness
- [ ] Kubernetes cluster is running (v1.24+)
- [ ] kubectl is configured and authenticated
- [ ] Cluster has sufficient resources:
  - [ ] Min 8 CPU cores available
  - [ ] Min 16GB RAM available
  - [ ] Min 100GB storage available
- [ ] StorageClass is configured for dynamic provisioning
- [ ] LoadBalancer service type is supported (for Traefik)

### ✅ Tools Installation
- [ ] kubectl installed and working
- [ ] helm v3.12+ installed
- [ ] AWS CLI installed (if using ECR)
- [ ] git installed

### ✅ Container Images
Choose ONE option:

**Option A: Docker Hub (Recommended for Testing)**
- [ ] Verify images are public and accessible:
  ```bash
  docker pull joshua76i/tradeflow-auth-service:1.0.0
  docker pull joshua76i/tradeflow-identity-service:1.0.0
  docker pull joshua76i/tradeflow-catalog-service:1.0.0
  docker pull joshua76i/tradeflow-inventory-service:1.0.0
  docker pull joshua76i/tradeflow-order-service:1.0.0
  docker pull joshua76i/tradeflow-payment-service:1.0.0
  docker pull joshua76i/tradeflow-fraud-service:1.0.0
  docker pull joshua76i/tradeflow-search-service:1.0.0
  ```
- [ ] If using Docker Hub, remove `imagePullSecrets` from deployments

**Option B: AWS ECR (Recommended for Production)**
- [ ] Images pushed to ECR repository
- [ ] AWS credentials configured
- [ ] ECR login successful:
  ```bash
  aws ecr get-login-password --region us-east-1 | \
    docker login --username AWS --password-stdin \
    765288911542.dkr.ecr.us-east-1.amazonaws.com
  ```
- [ ] ECR secret will be created during deployment

### ✅ Git Repository (for GitOps)
- [ ] Git repository created: `https://github.com/Solano204/tradeflow-gitops.git`
- [ ] Repository structure matches expected layout:
  ```
  tradeflow-gitops/
  ├── infrastructure/
  ├── services/
  └── gateway/
  ```
- [ ] ArgoCD application files reference correct repo URL

### ✅ Network Configuration
- [ ] Cluster network allows pod-to-pod communication
- [ ] External LoadBalancer can be provisioned
- [ ] DNS resolution works in cluster
- [ ] No network policies blocking traffic (or configured correctly)

### ✅ Security
- [ ] Reviewed and updated default passwords in:
  - [ ] PostgreSQL secrets (all 7 instances)
  - [ ] Grafana admin password
  - [ ] ArgoCD admin password (auto-generated)
- [ ] Reviewed and updated JWT secrets if needed
- [ ] TLS/HTTPS certificates ready (if using)

## Deployment Steps Checklist

### Step 1: Clone Repository
- [ ] Clone this repository:
  ```bash
  git clone <this-repo>
  cd tradeflow-k8s
  ```

### Step 2: Review Configuration
- [ ] Review `base/00-namespace.yaml`
- [ ] Review `base/infrastructure/*` manifests
- [ ] Review `base/services/*` configurations
- [ ] Update any environment-specific values

### Step 3: Automated Deployment (Recommended)
- [ ] Run deployment script:
  ```bash
  chmod +x deploy.sh
  ./deploy.sh
  ```
- [ ] Follow prompts and provide required information
- [ ] Wait for deployment to complete

### OR Step 3-9: Manual Deployment

#### Step 3: Install ArgoCD
- [ ] Create ArgoCD namespace
- [ ] Install ArgoCD
- [ ] Wait for ArgoCD to be ready
- [ ] Get admin password
- [ ] (Optional) Access ArgoCD UI

#### Step 4: Install Traefik
- [ ] Add Traefik Helm repository
- [ ] Install Traefik with Gateway API support
- [ ] Verify Traefik is running

#### Step 5: Create Namespaces
- [ ] Apply namespace manifests
- [ ] Verify namespaces created

#### Step 6: Create Image Pull Secret
- [ ] Create ECR or Docker Hub secret
- [ ] Verify secret in namespace

#### Step 7: Deploy Infrastructure
- [ ] Deploy Kafka
- [ ] Deploy all PostgreSQL instances
- [ ] Deploy all MongoDB instances
- [ ] Deploy all Redis instances
- [ ] Deploy Elasticsearch
- [ ] Deploy observability stack
- [ ] Wait for all infrastructure pods to be ready

#### Step 8: Deploy Services
- [ ] Deploy auth-service
- [ ] Deploy identity-service
- [ ] Deploy catalog-service
- [ ] Deploy inventory-service
- [ ] Deploy order-service
- [ ] Deploy payment-service
- [ ] Deploy fraud-service
- [ ] Deploy search-service
- [ ] Wait for all service pods to be ready

#### Step 9: Deploy Gateway
- [ ] Apply gateway configuration
- [ ] Verify gateway is running
- [ ] Verify HTTPRoute is configured

## Post-Deployment Verification

### Infrastructure Verification
- [ ] Check all infrastructure pods are running:
  ```bash
  kubectl get pods -n tradeflow-infrastructure
  ```
- [ ] Verify StatefulSets are ready:
  ```bash
  kubectl get statefulsets -n tradeflow-infrastructure
  ```
- [ ] Check PVCs are bound:
  ```bash
  kubectl get pvc -n tradeflow-infrastructure
  ```
- [ ] Verify Kafka topics created:
  ```bash
  kubectl exec kafka-0 -n tradeflow-infrastructure -- \
    kafka-topics --list --bootstrap-server localhost:29092
  ```

### Application Verification
- [ ] Check all service pods are running:
  ```bash
  kubectl get pods -n tradeflow-production
  ```
- [ ] Verify all deployments are ready:
  ```bash
  kubectl get deployments -n tradeflow-production
  ```
- [ ] Check service endpoints:
  ```bash
  kubectl get endpoints -n tradeflow-production
  ```

### Health Checks
- [ ] Test auth-service health:
  ```bash
  kubectl run -it --rm test --image=curlimages/curl --restart=Never -- \
    curl http://auth-service.tradeflow-production.svc.cluster.local:8080/q/health
  ```
- [ ] Test identity-service health:
  ```bash
  kubectl run -it --rm test --image=curlimages/curl --restart=Never -- \
    curl http://identity-service.tradeflow-production.svc.cluster.local:8081/actuator/health
  ```
- [ ] Repeat for other services

### Gateway Verification
- [ ] Get Traefik LoadBalancer IP:
  ```bash
  kubectl get svc -n traefik traefik
  ```
- [ ] Add IP to /etc/hosts:
  ```
  <EXTERNAL-IP> api.tradeflow.local tradeflow.local
  ```
- [ ] Test gateway routing:
  ```bash
  curl http://api.tradeflow.local/api/auth/q/health
  curl http://api.tradeflow.local/api/identity/actuator/health
  ```

### Observability Verification
- [ ] Access Grafana:
  ```bash
  kubectl port-forward svc/grafana -n tradeflow-infrastructure 3000:3000
  ```
  Visit http://localhost:3000 (admin/tradeflow123)

- [ ] Access Zipkin:
  ```bash
  kubectl port-forward svc/zipkin -n tradeflow-infrastructure 9411:9411
  ```
  Visit http://localhost:9411

- [ ] Access Prometheus:
  ```bash
  kubectl port-forward svc/prometheus -n tradeflow-infrastructure 9090:9090
  ```
  Visit http://localhost:9090

### GitOps Verification (if using ArgoCD)
- [ ] Check ArgoCD applications:
  ```bash
  kubectl get applications -n argocd
  ```
- [ ] Verify all apps are synced and healthy
- [ ] Access ArgoCD UI:
  ```bash
  kubectl port-forward svc/argocd-server -n argocd 8080:443
  ```
  Visit https://localhost:8080

## Functional Testing

### Database Connectivity
- [ ] Test PostgreSQL connections for each service
- [ ] Test MongoDB connections where applicable
- [ ] Test Redis connections

### Service Integration
- [ ] Create test user via identity-service
- [ ] Authenticate via auth-service
- [ ] Create test product via catalog-service
- [ ] Update inventory via inventory-service
- [ ] Create test order via order-service
- [ ] Process test payment via payment-service
- [ ] Verify search indexing via search-service

### Event Streaming
- [ ] Verify Kafka consumers are processing events
- [ ] Check Kafka consumer groups
- [ ] Monitor event lag

## Performance Verification

### Resource Usage
- [ ] Check node resource usage:
  ```bash
  kubectl top nodes
  ```
- [ ] Check pod resource usage:
  ```bash
  kubectl top pods -n tradeflow-production
  kubectl top pods -n tradeflow-infrastructure
  ```
- [ ] Verify no pods are being OOMKilled
- [ ] Verify no CPU throttling

### Response Times
- [ ] Measure API response times
- [ ] Check distributed tracing in Zipkin
- [ ] Verify database query performance

## Security Verification

### Network Security
- [ ] Verify pod-to-pod communication works
- [ ] Verify services are not externally accessible (except via gateway)
- [ ] Check network policies (if configured)

### Secret Management
- [ ] Verify secrets are not exposed in logs
- [ ] Verify secrets are properly mounted
- [ ] Verify ECR/Docker secret is working

### RBAC (if configured)
- [ ] Verify service accounts
- [ ] Verify role bindings
- [ ] Test access controls

## Monitoring Setup

### Metrics
- [ ] Configure Prometheus scrape targets
- [ ] Import Grafana dashboards
- [ ] Set up alerts in Prometheus/Grafana

### Logs
- [ ] Verify application logs are accessible via kubectl
- [ ] (Optional) Set up log aggregation (ELK, Loki)

### Traces
- [ ] Verify traces appear in Zipkin
- [ ] Test end-to-end request tracing

## Documentation

- [ ] Document any custom configuration changes
- [ ] Document external IP addresses
- [ ] Document DNS entries
- [ ] Document access credentials
- [ ] Create runbook for common operations
- [ ] Document backup procedures

## Handover

- [ ] Provide credentials to operations team
- [ ] Train team on kubectl basics
- [ ] Train team on ArgoCD usage
- [ ] Demonstrate monitoring dashboards
- [ ] Explain scaling procedures
- [ ] Explain incident response procedures

## Production Readiness (Additional for Production)

### High Availability
- [ ] Increase replica counts (min 3 for critical services)
- [ ] Configure Pod Disruption Budgets
- [ ] Set up multi-AZ deployment
- [ ] Configure database replication

### Backup & Recovery
- [ ] Set up automated database backups
- [ ] Test backup restoration procedure
- [ ] Document disaster recovery plan
- [ ] Test failover procedures

### Security Hardening
- [ ] Enable TLS/HTTPS for all external endpoints
- [ ] Configure network policies
- [ ] Enable Pod Security Policies/Admission
- [ ] Scan images for vulnerabilities
- [ ] Implement secret rotation

### Performance Tuning
- [ ] Configure Horizontal Pod Autoscaling
- [ ] Tune JVM settings for services
- [ ] Optimize database connections
- [ ] Configure resource requests/limits based on load testing

### Compliance
- [ ] Enable audit logging
- [ ] Configure log retention policies
- [ ] Implement access controls
- [ ] Document compliance requirements

## Troubleshooting Reference

If issues occur, use the helper script:
```bash
./helper.sh
```

Or refer to:
- `QUICKREF.md` - Quick command reference
- `README.md` - Comprehensive deployment guide
- `STRUCTURE.md` - Architecture and structure details

## Rollback Plan

If deployment fails:
1. Run cleanup script:
   ```bash
   ./cleanup.sh
   ```
2. Review logs for errors
3. Fix issues
4. Retry deployment

## Sign-off

- [ ] Deployment completed successfully
- [ ] All verification checks passed
- [ ] Team trained and ready
- [ ] Documentation complete
- [ ] Deployment approved for production (if applicable)

---

**Deployment Date:** _________________

**Deployed By:** _________________

**Approved By:** _________________

**Notes:**
