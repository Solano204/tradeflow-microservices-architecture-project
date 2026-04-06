#!/bin/bash
# ============================================================
# TradeFlow — Build, Tag & Push all service images to Docker Hub
# Usage: ./push-to-dockerhub.sh
# Requires: docker login already done as joshua76i
# ============================================================

set -e

DOCKERHUB_USER="joshua76i"
VERSION="1.0.0"

SERVICES=(
  "auth-service"
  "identity-service"
  "catalog-service"
  "inventory-service"
  "order-service"
  "payment-service"
  "fraud-service"
  "search-service"
)

echo "============================================================"
echo " TradeFlow — Docker Hub push for user: $DOCKERHUB_USER"
echo "============================================================"

# ── 1. Login check ────────────────────────────────────────────
echo ""
echo "Checking Docker Hub login..."
docker info | grep -i "username" || {
  echo "Not logged in. Logging in now..."
  docker login --username "$DOCKERHUB_USER"
}

# ── 2. Build all services via docker compose ──────────────────
echo ""
echo "Building all service images..."
docker compose -f docker-compose.services.yml build

# ── 3. Tag & Push each service ───────────────────────────────B
echo ""
echo "Tagging and pushing images..."

for SERVICE in "${SERVICES[@]}"; do
  LOCAL_IMAGE="tradeflow/${SERVICE}:${VERSION}"
  REMOTE_IMAGE="${DOCKERHUB_USER}/${SERVICE}:${VERSION}"
  REMOTE_LATEST="${DOCKERHUB_USER}/${SERVICE}:latest"

  echo ""
  echo "──── $SERVICE ────"
  echo "  Local:  $LOCAL_IMAGE"
  echo "  Remote: $REMOTE_IMAGE"

  docker tag "$LOCAL_IMAGE" "$REMOTE_IMAGE"
  docker tag "$LOCAL_IMAGE" "$REMOTE_LATEST"

  docker push "$REMOTE_IMAGE"
  docker push "$REMOTE_LATEST"

  echo "  ✓ Pushed $REMOTE_IMAGE"
done

echo ""
echo "============================================================"
echo " All images pushed to Docker Hub successfully!"
echo " Pull on any machine with:"
echo "   docker pull ${DOCKERHUB_USER}/auth-service:${VERSION}"
echo "============================================================"