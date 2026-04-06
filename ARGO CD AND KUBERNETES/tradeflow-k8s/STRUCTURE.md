# TradeFlow Kubernetes Project Structure

## Directory Layout

```
tradeflow-k8s/
├── README.md                          # Comprehensive deployment guide
├── deploy.sh                          # Automated deployment script
├── cleanup.sh                         # Cleanup/teardown script
├── helper.sh                          # Interactive helper for operations
│
├── base/                              # Base Kubernetes manifests
│   ├── 00-namespace.yaml             # Namespace definitions
│   ├── 01-ecr-secret.yaml            # Image pull secret template
│   ├── gateway.yaml                   # API Gateway configuration
│   │
│   ├── infrastructure/                # Infrastructure layer
│   │   ├── kafka.yaml                # Kafka + Zookeeper + Topics
│   │   ├── postgres.yaml             # All PostgreSQL databases
│   │   ├── mongodb.yaml              # All MongoDB instances
│   │   ├── redis.yaml                # All Redis instances
│   │   ├── elasticsearch.yaml        # Elasticsearch for search
│   │   └── observability.yaml        # Zipkin, Prometheus, Grafana
│   │
│   └── services/                      # Application services
│       ├── auth-service/
│       │   ├── config.yaml           # ConfigMap + Secret
│       │   └── deployment.yaml       # Deployment + Service
│       ├── identity-service/
│       │   ├── config.yaml
│       │   └── deployment.yaml
│       ├── catalog-service/
│       │   └── all.yaml              # Combined manifest
│       ├── inventory-service/
│       │   └── all.yaml
│       ├── order-service/
│       │   └── all.yaml
│       ├── payment-service/
│       │   └── all.yaml
│       ├── fraud-service/
│       │   └── all.yaml
│       └── search-service/
│           └── all.yaml
│
└── argocd/                            # ArgoCD Applications
    ├── infrastructure-app.yaml        # Infrastructure GitOps app
    ├── services-apps.yaml             # All service apps
    └── gateway-app.yaml               # Gateway GitOps app
```

## Resource Inventory

### Namespaces
- `tradeflow-infrastructure` - All infrastructure components
- `tradeflow-production` - All application services
- `argocd` - ArgoCD control plane
- `traefik` - Traefik Gateway

### Infrastructure Components

#### Kafka Ecosystem
- `zookeeper` (StatefulSet) - Coordination service
- `kafka` (StatefulSet) - Message broker
- `kafka-topics-init` (Job) - Topic creation
- 22 Kafka topics for event streaming

#### Databases - PostgreSQL (7 instances)
- `auth-postgres` - Auth service database
- `identity-postgres` - Identity service database
- `catalog-postgres` - Catalog service database
- `inventory-postgres` - Inventory service database
- `order-postgres` - Order service database
- `payment-postgres` - Payment service database
- `fraud-postgres` - Fraud detection database

#### Databases - MongoDB (4 instances)
- `identity-mongodb` - Identity read models
- `catalog-mongodb` - Catalog details
- `payment-mongodb` - Payment audit logs
- `fraud-mongodb` - Fraud analysis data

#### Cache - Redis (6 instances)
- `auth-redis` - Auth sessions
- `identity-redis` - Identity cache
- `catalog-redis` - Catalog cache
- `payment-redis` - Payment idempotency
- `fraud-redis` - Fraud detection cache
- `search-redis` - Search results cache

#### Search
- `search-elasticsearch` - Full-text search engine

#### Observability
- `zipkin` - Distributed tracing
- `prometheus` - Metrics collection
- `grafana` - Metrics visualization

### Application Services

All services run with 2 replicas for high availability:

1. **auth-service** (Port 8080, Quarkus)
   - JWT authentication
   - Session management
   - User authentication

2. **identity-service** (Port 8081, Spring Boot)
   - User identity management
   - KYC verification
   - Profile management

3. **catalog-service** (Port 8082, Spring Boot)
   - Product catalog
   - Category management
   - Product details

4. **inventory-service** (Port 8083, Spring Boot)
   - Stock management
   - Inventory tracking
   - Reservation handling

5. **order-service** (Port 8084, Spring Boot)
   - Order processing
   - Order lifecycle
   - Order orchestration

6. **payment-service** (Port 8085, Quarkus)
   - Payment processing
   - Transaction management
   - Stripe integration (mocked)

7. **fraud-service** (Port 8087, Spring Boot)
   - Fraud detection
   - Risk scoring
   - Pattern analysis

8. **search-service** (Port 8089, Spring Boot)
   - Product search
   - Search indexing
   - Query processing

### API Gateway

- Gateway API (Traefik)
- HTTPRoute for all services
- Path-based routing:
  - `/api/auth/*` → auth-service
  - `/api/identity/*` → identity-service
  - `/api/catalog/*` → catalog-service
  - `/api/inventory/*` → inventory-service
  - `/api/orders/*` → order-service
  - `/api/payments/*` → payment-service
  - `/api/fraud/*` → fraud-service
  - `/api/search/*` → search-service

## Resource Requirements

### Per Service Defaults
```yaml
resources:
  requests:
    memory: "512Mi"
    cpu: "250m"
  limits:
    memory: "1Gi"
    cpu: "500m"
```

### Infrastructure Components
- **Kafka**: 1Gi memory, 500m CPU
- **PostgreSQL**: 512Mi memory, 500m CPU each
- **MongoDB**: 512Mi memory, 500m CPU each
- **Redis**: 256Mi memory, 200m CPU each
- **Elasticsearch**: 2Gi memory, 1000m CPU
- **Observability Stack**: ~2Gi memory total

### Total Cluster Requirements
- **Minimum**: 8 CPU cores, 16GB RAM
- **Recommended**: 16 CPU cores, 32GB RAM
- **Storage**: 100-200GB for persistent volumes

## Persistent Volumes

Each StatefulSet gets its own PVC:
- PostgreSQL: 10Gi per instance
- MongoDB: 10Gi per instance
- Redis: 2Gi per instance
- Kafka: 20Gi
- Zookeeper: 10Gi (data) + 5Gi (logs)
- Elasticsearch: 20Gi

Total storage: ~150Gi

## Network Architecture

### Service Communication
- All services communicate via Kubernetes Services (ClusterIP)
- Internal DNS: `<service>.<namespace>.svc.cluster.local`
- Kafka as event bus for async communication
- HTTP/REST for synchronous calls

### External Access
- Traefik LoadBalancer for external traffic
- Gateway API for routing rules
- Hostname: `api.tradeflow.local`

## Configuration Management

### ConfigMaps
Each service has a ConfigMap with:
- Database URLs
- Kafka bootstrap servers
- Redis connection strings
- Service discovery URLs
- Feature flags

### Secrets
Each service has a Secret with:
- Database credentials
- API keys
- JWT secrets (where applicable)

## GitOps with ArgoCD

### Repository Structure (Expected)
```
tradeflow-gitops/
├── infrastructure/
│   └── (copy all from base/infrastructure/)
├── services/
│   ├── auth-service/
│   ├── identity-service/
│   └── ...
└── gateway/
    └── gateway.yaml
```

### Sync Policies
- **Automated sync**: Enabled
- **Self-heal**: Enabled
- **Prune**: Enabled
- **Retry**: 5 attempts with exponential backoff

## Health Checks

### Quarkus Services (auth, payment)
- Readiness: `/q/health/ready`
- Liveness: `/q/health/live`
- Metrics: `/q/metrics`

### Spring Boot Services (all others)
- Readiness: `/actuator/health/readiness`
- Liveness: `/actuator/health/liveness`
- Metrics: `/actuator/prometheus`

## Security Considerations

1. **Image Pull Secrets**: ECR authentication required for production
2. **Network Policies**: Not implemented (add as needed)
3. **Pod Security**: Not implemented (add PSP/PSA as needed)
4. **Secrets Management**: Using Kubernetes Secrets (consider external secret managers)
5. **RBAC**: Default ServiceAccount (customize as needed)

## Scaling Strategies

### Horizontal Pod Autoscaling
Not configured by default. Add HPA:
```bash
kubectl autoscale deployment auth-service \
  --cpu-percent=70 --min=2 --max=10 \
  -n tradeflow-production
```

### Vertical Scaling
Adjust resource requests/limits in deployments

### Database Scaling
- PostgreSQL: Can increase to 3 replicas with replication
- MongoDB: Can configure replica sets
- Redis: Can add Redis Sentinel for HA

## Monitoring & Observability

### Metrics (Prometheus)
- Service metrics: `/actuator/prometheus` or `/q/metrics`
- Infrastructure metrics: Node exporter, kube-state-metrics
- Retention: 15 days

### Traces (Zipkin)
- All services configured with OTEL/Zipkin
- UI: Port 9411

### Logs
- Stdout/stderr (captured by Kubernetes)
- View with `kubectl logs`
- Consider adding log aggregation (ELK, Loki)

### Dashboards (Grafana)
- Pre-configured datasources
- Import dashboards as needed
- Default credentials: admin/tradeflow123

## Backup Strategy

### Databases
Not implemented. Recommended:
- PostgreSQL: `pg_dump` via CronJob
- MongoDB: `mongodump` via CronJob
- Store backups in S3/GCS

### Configuration
- All manifests in Git (GitOps)
- Secrets should be backed up securely

## Disaster Recovery

### Infrastructure
- StatefulSets with PVCs preserve data
- PV reclaim policy should be "Retain" in production

### Application
- Deployments are stateless
- Can be recreated from Git
- Data in databases/Kafka should be backed up

## Performance Tuning

### JVM Settings (Not configured, add via env vars)
```yaml
- name: JAVA_OPTS
  value: "-Xms512m -Xmx1g -XX:+UseG1GC"
```

### Database Connections
- Configure connection pools in application.properties
- Adjust based on load

### Kafka Tuning
- Partition count: 3 per topic
- Replication factor: 1 (increase to 3 in production)

## CI/CD Integration

### Image Building
- Build with Docker/Buildah
- Tag: `<service>:1.0.0`
- Push to ECR or Docker Hub

### Deployment
1. Update image tag in manifests
2. Commit to Git
3. ArgoCD auto-syncs
4. Or trigger sync via ArgoCD API/CLI

### Rollback
```bash
kubectl rollout undo deployment/auth-service -n tradeflow-production
```

## Maintenance Tasks

### Regular Tasks
- Update ECR secret (expires after 12 hours)
- Monitor disk usage on PVs
- Review logs for errors
- Check resource usage

### Upgrades
- Update image tags in Git
- ArgoCD handles rolling updates
- Monitor rollout status

### Cleanup
- Old PVCs may persist
- Remove orphaned PVs manually
- Clean up completed jobs
