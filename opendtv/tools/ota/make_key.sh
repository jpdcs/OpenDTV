#!/bin/sh
# Makes the key pair that signs over-the-air logic bundles (see app/src/main/java/ph/opendtv/ota/LogicBundles.java).
#
#   tools/ota/make_key.sh [ota-signing-key.pem]
#
# Writes the PRIVATE key to the .pem (default ota-signing-key.pem, outside the repo is best) and prints the PUBLIC key.
#   - Paste the printed public key into LogicBundles.PUBLIC_KEY and release a new APK: from then on that APK accepts
#     bundles signed with this key, and only those.
#   - Put the whole .pem file into the repository secret OTA_SIGNING_KEY (Settings > Secrets and variables >
#     Actions); .github/workflows/ota-bundle.yml signs with it.
#   - Never commit the .pem. Losing it means a new key and a new APK; leaking it means anyone can push code to users.
set -e
KEY="${1:-ota-signing-key.pem}"
[ -e "$KEY" ] && { echo "$KEY already exists; not overwriting a key"; exit 1; }
umask 077
openssl ecparam -name prime256v1 -genkey -noout -out "$KEY"
echo "private key: $KEY (keep it secret, see above)"
echo "public key for LogicBundles.PUBLIC_KEY:"
openssl ec -in "$KEY" -pubout -outform DER 2>/dev/null | base64 | tr -d '\n'
echo
