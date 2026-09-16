#!/bin/bash
# Script to remove emojis from Java log statements across all microservices

echo "Removing emojis from all microservices..."

cd "C:\Users\KIIT0001\Desktop\banking system"

# Define emoji to text replacements
declare -A replacements=(
    ["🚀"]="[START]"
    ["💸"]="[DEDUCT]"
    ["💰"]="[BALANCE]"
    ["🔍"]="[CHECK]"
    ["✅"]="[SUCCESS]"
    ["❌"]="[FAILED]"
    ["🚨"]="[ALERT]"
    ["📤"]="[PUBLISH]"
    ["💾"]="[SAVE]"
    ["🔐"]="[OTP]"
    ["🔄"]="[COMPENSATION]"
    ["🏦"]="[ACCOUNT]"
)

# Function to replace emojis in a file
replace_emojis() {
    local file=$1
    echo "Processing: $file"

    # Remove emojis using sed
    sed -i 's/🚀/[START]/g; s/💸/[DEDUCT]/g; s/💰/[BALANCE]/g; s/🔍/[CHECK]/g; s/✅/[SUCCESS]/g; s/❌/[FAILED]/g; s/🚨/[ALERT]/g; s/📤/[PUBLISH]/g; s/💾/[SAVE]/g; s/🔐/[OTP]/g; s/🔄/[COMPENSATION]/g; s/🏦/[ACCOUNT]/g' "$file"
}

export -f replace_emojis

# Find all Java files and replace emojis
find . -name "*.java" -type f -exec bash -c 'replace_emojis "$0"' {} \;

echo "Emoji replacement completed!"
echo "Rebuilding all services..."

# Rebuild all services
for service in account-service transaction-service fraud-detection-service payment-service notification-service api-gateway; do
    echo "Building $service..."
    cd "$service"
    mvn clean compile -q
    cd ..
done

echo "All services rebuilt successfully!"
