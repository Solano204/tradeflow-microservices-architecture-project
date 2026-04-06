#!/bin/bash
# ============================================================
# TradeFlow — Full Stack Docker Startup Script
# Usage: ./start.sh [build|up|down|logs|status]
# ============================================================

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

INFRA_COMPOSE="docker compose -f docker-compose.infra.yml"
SERVICES_COMPOSE="docker compose -f docker-compose.services.yml"

log() { echo -e "${BLUE}[TradeFlow]${NC} $1"; }
success() { echo -e "${GREEN}[✓]${NC} $1"; }
warn() { echo -e "${YELLOW}[!]${NC} $1"; }
error() { echo -e "${RED}[✗]${NC} $1"; exit 1; }

check_env() {
  if [ ! -f .env ]; then
    warn ".env not found — copying from .env.example"
    cp .env.example .env
    warn "Please edit .env with real values if needed, then re-run."
  fi
}

build_all() {
  log "Building all service images..."
  $SERVICES_COMPOSE build --parallel
  success "All images built"
}

start_infra() {
  log "Starting infrastructure (Kafka, Postgres, Redis, MongoDB, ES, Zipkin)..."
  $INFRA_COMPOSE up -d

  log "Waiting for Kafka to be healthy..."
  until docker exec tradeflow-kafka kafka-broker-api-versions --bootstrap-server localhost:9092 > /dev/null 2>&1; do
    echo -n "."
    sleep 2
  done
  echo ""
  success "Kafka ready"

  log "Waiting for topic initialization to complete..."
  docker wait tradeflow-topic-init 2>/dev/null || true
  success "Topics created"
}

start_services() {
  log "Starting all microservices..."
  # Auth must start first — all others depend on its JWKS endpoint
  $SERVICES_COMPOSE up -d tradeflow-auth-service
  log "Waiting for Auth Service..."
  until curl -sf http://localhost:8080/q/health/live > /dev/null 2>&1; do
    echo -n "."
    sleep 3
  done
  echo ""
  success "Auth Service ready"

  # Start remaining services in parallel
  $SERVICES_COMPOSE up -d
  success "All services started"
}

case "${1:-up}" in
  build)
    check_env
    build_all
    ;;
  up)
    check_env
    start_infra
    start_services
    echo ""
    echo -e "${GREEN}============================================================${NC}"
    echo -e "${GREEN}  TradeFlow is running!${NC}"
    echo -e "${GREEN}============================================================${NC}"
    echo ""
    echo "  🔐 Auth Service:      http://localhost:8080"
    echo "  👤 Identity Service:  http://localhost:8081"
    echo "  📦 Catalog Service:   http://localhost:8082"
    echo "  📊 Inventory Service: http://localhost:8083"
    echo "  🛒 Order Service:     http://localhost:8084"
    echo "  💳 Payment Service:   http://localhost:8085"
    echo "  🛡️  Fraud Service:     http://localhost:8087"
    echo "  🔍 Search Service:    http://localhost:8089"
    echo ""
    echo "  📈 Kafka UI:    http://localhost:8095"
    echo "  🔭 Zipkin:      http://localhost:9411"
    echo "  📊 Grafana:     http://localhost:3000  (admin/tradeflow123)"
    echo "  📊 Prometheus:  http://localhost:9090"
    echo "  🔍 Kibana:      http://localhost:5601"
    echo "  🪣 LocalStack:  http://localhost:4566"
    echo ""
    ;;
  down)
    log "Stopping all services..."
    $SERVICES_COMPOSE down
    $INFRA_COMPOSE down
    success "All stopped"
    ;;
  down-volumes)
    warn "Stopping and REMOVING ALL VOLUMES (data will be lost)..."
    $SERVICES_COMPOSE down -v
    $INFRA_COMPOSE down -v
    success "All stopped and volumes removed"
    ;;
  logs)
    SERVICE=${2:-}
    if [ -n "$SERVICE" ]; then
      $SERVICES_COMPOSE logs -f "$SERVICE"
    else
      $SERVICES_COMPOSE logs -f
    fi
    ;;
  status)
    echo "=== INFRASTRUCTURE ==="
    $INFRA_COMPOSE ps
    echo ""
    echo "=== SERVICES ==="
    $SERVICES_COMPOSE ps
    ;;
  restart)
    SERVICE=${2:-}
    if [ -n "$SERVICE" ]; then
      log "Restarting $SERVICE..."
      $SERVICES_COMPOSE restart "$SERVICE"
    else
      log "Restarting all services..."
      $SERVICES_COMPOSE restart
    fi
    ;;
  *)
    echo "Usage: $0 [build|up|down|down-volumes|logs [service]|status|restart [service]]"
    exit 1
    ;;
esac