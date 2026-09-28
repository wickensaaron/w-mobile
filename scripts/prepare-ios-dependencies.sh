#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "$0")/.." && pwd -P)"
engine_version=0.1.1
engine_checksum=24905c0484b2e5c886c2685ce03e5f5585c3dc6096c65c59948b35be56ae4dc0
engine_root="${NUVIO_ENGINE_ROOT:-${repository_root}/../nuvio-engine}"
engine_framework="${engine_root}/platform/apple/NuvioEngine.xcframework"

expected_mpv_commit="$(git -C "${repository_root}" ls-files --stage MPVKit | awk '$1 == "160000" {print $2}')"
if [[ ! "${expected_mpv_commit}" =~ ^[0-9a-f]{40}$ ]]; then
    echo "The source checkout does not contain a pinned MPVKit submodule." >&2
    exit 1
fi
actual_mpv_commit="$(git -C "${repository_root}/MPVKit" rev-parse HEAD 2>/dev/null || true)"
if [[ ! -f "${repository_root}/MPVKit/Package.swift" || "${actual_mpv_commit}" != "${expected_mpv_commit}" ]]; then
    git -C "${repository_root}" submodule update --init --depth 1 MPVKit
fi
actual_mpv_commit="$(git -C "${repository_root}/MPVKit" rev-parse HEAD)"
if [[ "${actual_mpv_commit}" != "${expected_mpv_commit}" || ! -f "${repository_root}/MPVKit/Package.swift" ]]; then
    echo "MPVKit preparation did not produce the source checkout's pinned package." >&2
    exit 1
fi
if ! git -C "${repository_root}/MPVKit" diff --quiet ||
   ! git -C "${repository_root}/MPVKit" diff --cached --quiet; then
    echo "MPVKit contains local tracked changes; preserve them before preparing a pinned IPA." >&2
    exit 1
fi

validate_engine_framework() {
    local framework="$1"
    local slice
    if [[ ! -f "${framework}/Info.plist" ]]; then
        echo "Nuvio Engine Apple framework is missing Info.plist." >&2
        return 1
    fi
    # Gradle configures both targets even when Xcode builds only a device IPA.
    for slice in ios-arm64 ios-arm64_x86_64-simulator; do
        if [[ ! -f "${framework}/${slice}/libCNuvioEngine.a" ||
              ! -f "${framework}/${slice}/Headers/nuvio_engine/nuvio_engine.h" ]]; then
            echo "Nuvio Engine Apple framework is missing the ${slice} library or headers." >&2
            return 1
        fi
    done
}

if [[ -f "${engine_framework}/Info.plist" ]]; then
    validate_engine_framework "${engine_framework}"
    exit 0
fi

temporary_directory="$(mktemp -d "${TMPDIR:-/tmp}/nuvio-ios-dependencies.XXXXXX")"
trap 'rm -rf "${temporary_directory}"' EXIT

archive="${temporary_directory}/nuvio-engine-apple-${engine_version}.zip"
curl --fail --location --retry 5 --retry-all-errors --silent --show-error \
    --output "${archive}" \
    "https://github.com/NuvioMedia/nuvio-engine/releases/download/v${engine_version}/nuvio-engine-apple-${engine_version}.zip"

actual_checksum="$(shasum -a 256 "${archive}" | awk '{print $1}')"
if [[ "${actual_checksum}" != "${engine_checksum}" ]]; then
    echo "Nuvio Engine Apple package checksum mismatch." >&2
    exit 1
fi

extraction_root="${temporary_directory}/extracted"
mkdir -p "${extraction_root}"
unzip -q "${archive}" -d "${extraction_root}"
source_framework="$(find "${extraction_root}" -type d -name NuvioEngine.xcframework -print -quit)"
if [[ -z "${source_framework}" || ! -f "${source_framework}/Info.plist" ]]; then
    echo "Nuvio Engine Apple package does not contain NuvioEngine.xcframework." >&2
    exit 1
fi
validate_engine_framework "${source_framework}"

mkdir -p "$(dirname "${engine_framework}")"
ditto "${source_framework}" "${engine_framework}"
