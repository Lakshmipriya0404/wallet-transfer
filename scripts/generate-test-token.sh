#!/bin/bash
set -e

# Dynamically generates a JWT token using the provided JWT_SECRET
# Usage: ./generate-test-token.sh <user_id>

if [ -z "$JWT_SECRET" ]; then
    echo "Error: JWT_SECRET environment variable is not set."
    exit 1
fi

if [ -z "$1" ]; then
    echo "Usage: $0 <user_id>"
    exit 1
fi

USER_ID=$1
HEADER='{"alg":"HS256","typ":"JWT"}'
PAYLOAD="{\"sub\":\"${USER_ID}\"}"

# Base64Url encode helper
base64url_encode() {
    # Encode with base64, translate +/ to -_ and remove padding =
    echo -n "$1" | base64 | tr '+/' '-_' | tr -d '=' | tr -d '\n'
}

HEADER_B64=$(base64url_encode "$HEADER")
PAYLOAD_B64=$(base64url_encode "$PAYLOAD")

SIGNATURE=$(echo -n "${HEADER_B64}.${PAYLOAD_B64}" | openssl dgst -sha256 -hmac "$JWT_SECRET" -binary | base64 | tr '+/' '-_' | tr -d '=' | tr -d '\n')

echo "${HEADER_B64}.${PAYLOAD_B64}.${SIGNATURE}"
