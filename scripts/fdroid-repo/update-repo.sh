#!/usr/bin/env bash
# SPDX-FileCopyrightText: Open Camera contributors
# SPDX-License-Identifier: Apache-2.0
#
# Publish a GitHub release APK to the self-hosted F-Droid repo
# (https://hoanglt1223.github.io/open-camera-fdroid/repo).
#
# Usage: scripts/fdroid-repo/update-repo.sh <tag>   e.g. v0.1.0-beta3
#
# The repo index is signed locally with the key referenced by
# $FDROID_REPO_DIR/config.yml. That key is deliberately never stored in CI.
set -euo pipefail

TAG=${1:?usage: $0 <tag>}
SRC_REPO=hoanglt1223/open-camera
PAGES_REPO=hoanglt1223/open-camera-fdroid
RELEASE_CERT_SHA256=999623e2de4a85557102624929916dcd80126aaf2354697dc0406efd0a345902
FDROID_REPO_DIR=${FDROID_REPO_DIR:-$HOME/fdroid-repo}
PAGES_DIR=${PAGES_DIR:-$HOME/open-camera-fdroid}
FDROID=${FDROID:-$HOME/.fdroid-venv/bin/fdroid}
APKSIGNER=${APKSIGNER:-$(ls -d "${ANDROID_HOME:-$HOME/android-sdk}"/build-tools/*/apksigner | sort -V | tail -1)}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)

[ -f "$FDROID_REPO_DIR/config.yml" ] || { echo "missing $FDROID_REPO_DIR/config.yml" >&2; exit 1; }

dl=$(mktemp -d)
trap 'rm -rf "$dl"' EXIT
gh release download "$TAG" -R "$SRC_REPO" -p '*.apk' -D "$dl"
apk=$(ls "$dl"/*.apk | grep -v -- '-foss\.apk$')
[ "$(echo "$apk" | wc -l)" -eq 1 ] || { echo "expected exactly one APK in $TAG" >&2; exit 1; }

signers=$("$APKSIGNER" verify --print-certs "$apk" | grep -c 'certificate SHA-256 digest' || true)
digest=$("$APKSIGNER" verify --print-certs "$apk" | sed -n 's/.*certificate SHA-256 digest: //p')
if [ "$signers" -ne 1 ] || [ "$digest" != "$RELEASE_CERT_SHA256" ]; then
  echo "signer mismatch: got '$digest'" >&2; exit 1
fi

meta="$FDROID_REPO_DIR/metadata/dev.opencamera.app"
mkdir -p "$meta"
cp -r "$ROOT"/fastlane/metadata/android/. "$meta"/
version=${TAG#v}
code=$("$(dirname "$APKSIGNER")"/aapt2 dump badging "$apk" 2>/dev/null | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" || true)
sed -i "s/^CurrentVersion:.*/CurrentVersion: $version/" "$FDROID_REPO_DIR/metadata/dev.opencamera.app.yml"
[ -n "$code" ] && sed -i "s/^CurrentVersionCode:.*/CurrentVersionCode: $code/" "$FDROID_REPO_DIR/metadata/dev.opencamera.app.yml"

cp "$apk" "$FDROID_REPO_DIR/repo/"
(cd "$FDROID_REPO_DIR" && "$FDROID" update --pretty)

[ -d "$PAGES_DIR/.git" ] || gh repo clone "$PAGES_REPO" "$PAGES_DIR"
git -C "$PAGES_DIR" pull -q --ff-only
# status/ carries build-host details; it is not part of the public repo.
rsync -a --delete --exclude status "$FDROID_REPO_DIR/repo/" "$PAGES_DIR/repo/"
if [ -d "$FDROID_REPO_DIR/archive" ]; then
  rsync -a --delete --exclude status "$FDROID_REPO_DIR/archive/" "$PAGES_DIR/archive/"
fi
git -C "$PAGES_DIR" add -A
if git -C "$PAGES_DIR" diff --cached --quiet; then
  echo "repo unchanged"; exit 0
fi
git -C "$PAGES_DIR" commit -qm "chore: publish $TAG"
git -C "$PAGES_DIR" push -q
echo "published $TAG"
