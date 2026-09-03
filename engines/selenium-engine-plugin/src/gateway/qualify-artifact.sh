#!/bin/sh
set -eu
export SOURCE_DATE_EPOCH=0
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
docker buildx build --platform linux/amd64 --target executable --output "type=local,dest=$work/first" -f Dockerfile.gateway .
docker buildx build --platform linux/amd64 --target executable --output "type=local,dest=$work/second" -f Dockerfile.gateway .
first="$(sha256sum "$work/first/gateway-entrypoint" | awk '{print $1}')"
second="$(sha256sum "$work/second/gateway-entrypoint" | awk '{print $1}')"
test "$first" = "$second"
command -v jq >/dev/null
docker buildx build --platform linux/amd64 --build-arg "GATEWAY_EXECUTABLE_DIGEST=$first" --output "type=oci,dest=$work/gateway-first.oci" -f Dockerfile.gateway .
docker buildx build --platform linux/amd64 --build-arg "GATEWAY_EXECUTABLE_DIGEST=$first" --output "type=oci,dest=$work/gateway-second.oci" -f Dockerfile.gateway .
mkdir "$work/oci-first" "$work/oci-second"
tar -xf "$work/gateway-first.oci" -C "$work/oci-first"
tar -xf "$work/gateway-second.oci" -C "$work/oci-second"
manifest="$(jq -er '.manifests | select(length == 1) | .[0].digest | select(startswith("sha256:"))' "$work/oci-first/index.json")"
second_manifest="$(jq -er '.manifests | select(length == 1) | .[0].digest | select(startswith("sha256:"))' "$work/oci-second/index.json")"
test "$manifest" = "$second_manifest"
manifest_hex="${manifest#sha256:}"
test "$(sha256sum "$work/oci-first/blobs/sha256/$manifest_hex" | awk '{print $1}')" = "$manifest_hex"
test "$(sha256sum "$work/oci-second/blobs/sha256/$manifest_hex" | awk '{print $1}')" = "$manifest_hex"
config="$(jq -er '.config.digest | select(startswith("sha256:"))' "$work/oci-first/blobs/sha256/$manifest_hex")"
config_hex="${config#sha256:}"
test "$(sha256sum "$work/oci-first/blobs/sha256/$config_hex" | awk '{print $1}')" = "$config_hex"
jq -e --arg executable "sha256:$first" '.config.Labels["com.automationstudio.executable-digest"] == $executable' "$work/oci-first/blobs/sha256/$config_hex" >/dev/null
archive="$(sha256sum "$work/gateway-first.oci" | awk '{print $1}')"
printf 'GATEWAY_EXECUTABLE_DIGEST=sha256:%s\nGATEWAY_OCI_MANIFEST_DIGEST=%s\nGATEWAY_OCI_CONFIG_DIGEST=%s\nGATEWAY_OCI_ARCHIVE_DIGEST=sha256:%s\n' "$first" "$manifest" "$config" "$archive"
