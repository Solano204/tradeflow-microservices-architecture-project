#!/bin/bash
# ============================================================
# TradeFlow Auth Service — JWT Key Generation Script
# ============================================================
# Run this ONCE to generate RSA key pair for JWT signing.
# In production: use HashiCorp Vault or AWS KMS instead.
#
# Usage: chmod +x generate-keys.sh && ./generate-keys.sh

set -e

KEYS_DIR="src/main/resources/keys"
mkdir -p "$KEYS_DIR"

echo "Generating RSA 2048 key pair for JWT signing (RS256)..."

# Generate private key (PKCS8 format required by SmallRye JWT)
openssl genrsa -out "$KEYS_DIR/jwt-private-key-raw.pem" 2048 2>/dev/null
openssl pkcs8 -topk8 -inform PEM -in "$KEYS_DIR/jwt-private-key-raw.pem" \
    -out "$KEYS_DIR/jwt-private-key.pem" -nocrypt
rm "$KEYS_DIR/jwt-private-key-raw.pem"

# Derive public key
openssl rsa -in "$KEYS_DIR/jwt-private-key.pem" \
    -pubout -out "$KEYS_DIR/jwt-public-key.pem" 2>/dev/null

echo "Keys generated:"
echo "  Private key: $KEYS_DIR/jwt-private-key.pem"
echo "  Public key:  $KEYS_DIR/jwt-public-key.pem"
echo ""
echo "⚠️  IMPORTANT:"
echo "  - Add 'src/main/resources/keys/' to .gitignore"
echo "  - In Kubernetes: mount these as Secrets at /etc/auth/keys/"
echo "  - In production: use Vault Transit or AWS KMS"
echo ""
echo "To update K8s secret:"
echo "  kubectl create secret generic auth-jwt-keys \\"
echo "    --from-file=jwt-private-key.pem=$KEYS_DIR/jwt-private-key.pem \\"
echo "    --from-file=jwt-public-key.pem=$KEYS_DIR/jwt-public-key.pem \\"
echo "    -n tradeflow --dry-run=client -o yaml | kubectl apply -f -"
