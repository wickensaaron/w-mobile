#!/usr/bin/env bash

set -euo pipefail

if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "An iPhone IPA requires macOS with Xcode. Prepare locally; run this script on the authorised Mac builder." >&2
    exit 1
fi
if ! command -v xcodebuild >/dev/null || ! xcodebuild -version >/dev/null 2>&1; then
    echo "Select a working Xcode installation before building the iPhone IPA." >&2
    exit 1
fi

repository_root="$(cd "$(dirname "$0")/.." && pwd -P)"
version_file="${repository_root}/iosApp/Configuration/Version.xcconfig"
version="${1:-$(sed -nE 's/^[[:space:]]*MARKETING_VERSION[[:space:]]*=[[:space:]]*([^[:space:]#]+).*$/\1/p' "${version_file}" | head -n 1)}"
configuration="${IOS_CONFIGURATION:-Release}"
case "${configuration}" in
    Debug)
        configuration_slug="debug"
        ;;
    Release)
        configuration_slug="release"
        ;;
    *)
        echo "Unsupported iOS configuration: ${configuration}" >&2
        exit 1
        ;;
esac
derived_data="${IOS_DERIVED_DATA_PATH:-${repository_root}/build/ios-derived-full-${configuration_slug}}"
output_directory="${IOS_IPA_OUTPUT_DIR:-${repository_root}/build/ios-ipa}"
clang_module_cache="${CLANG_MODULE_CACHE_PATH:-${derived_data}/ModuleCache.noindex}"
swiftpm_module_cache="${SWIFTPM_MODULECACHE_OVERRIDE:-${derived_data}/SwiftPMModuleCache.noindex}"

if [[ ! "${version}" =~ ^[0-9A-Za-z][0-9A-Za-z._-]*$ ]]; then
    echo "Invalid IPA version: ${version}" >&2
    exit 1
fi

cd "${repository_root}"
mkdir -p "${output_directory}"
output_directory="$(cd "${output_directory}" && pwd -P)"
build_log="${output_directory}/W-Media-Player-${version}-full-${configuration_slug}-build.log"
build_environment=(
    env
    NUVIO_IOS_DISTRIBUTION=full
    CLANG_MODULE_CACHE_PATH="${clang_module_cache}"
    SWIFTPM_MODULECACHE_OVERRIDE="${swiftpm_module_cache}"
)
xcode_build_settings=()
if [[ -n "${NUVIO_GRADLE_JVMARGS:-}" ]]; then
    xcode_build_settings+=("NUVIO_GRADLE_JVMARGS=${NUVIO_GRADLE_JVMARGS}")
fi
if [[ -n "${NUVIO_KOTLIN_NATIVE_JVMARGS:-}" ]]; then
    xcode_build_settings+=("NUVIO_KOTLIN_NATIVE_JVMARGS=${NUVIO_KOTLIN_NATIVE_JVMARGS}")
fi
if [[ -n "${NUVIO_GRADLE_MAX_WORKERS:-}" ]]; then
    if [[ ! "${NUVIO_GRADLE_MAX_WORKERS}" =~ ^[1-9][0-9]*$ ]]; then
        echo "NUVIO_GRADLE_MAX_WORKERS must be a positive integer." >&2
        exit 1
    fi
    xcode_build_settings+=("NUVIO_GRADLE_MAX_WORKERS=${NUVIO_GRADLE_MAX_WORKERS}")
fi
gradle_init_directory=""
if [[ -n "${NUVIO_GRADLE_EXPECTED_HEAP_MB:-}" ]]; then
    if [[ ! "${NUVIO_GRADLE_EXPECTED_HEAP_MB}" =~ ^[1-9][0-9]*$ ]]; then
        echo "NUVIO_GRADLE_EXPECTED_HEAP_MB must be a positive integer." >&2
        exit 1
    fi
    echo "Mac physical memory (bytes): $(sysctl -n hw.memsize)"
    echo "Requested Gradle heap: ${NUVIO_GRADLE_EXPECTED_HEAP_MB} MiB"
    gradle_init_directory="$(mktemp -d "${TMPDIR:-/tmp}/w-media-ios-gradle.XXXXXX")"
    trap '[[ -z "${gradle_init_directory}" ]] || rm -rf "${gradle_init_directory}"' EXIT
    cat > "${gradle_init_directory}/verify-heap.gradle" <<'EOF'
gradle.settingsEvaluated {
    def expectedMiB = System.getenv('NUVIO_GRADLE_EXPECTED_HEAP_MB').toLong()
    def actualMiB = Runtime.getRuntime().maxMemory() / (1024L * 1024L)
    println("W Media Player effective Gradle heap: ${actualMiB} MiB (requested ${expectedMiB} MiB)")
    if (actualMiB < expectedMiB * 0.9) {
        throw new GradleException('The requested cloud Gradle heap did not reach the daemon; stopping before native compilation.')
    }
}
EOF
    xcode_build_settings+=(
        "NUVIO_GRADLE_EXPECTED_HEAP_MB=${NUVIO_GRADLE_EXPECTED_HEAP_MB}"
        "NUVIO_GRADLE_INIT_SCRIPT=${gradle_init_directory}/verify-heap.gradle"
    )
fi
"${build_environment[@]}" \
    xcodebuild \
    -project iosApp/iosApp.xcodeproj \
    -scheme iosApp \
    -configuration "${configuration}" \
    -sdk iphoneos \
    -destination 'generic/platform=iOS' \
    -derivedDataPath "${derived_data}" \
    CODE_SIGNING_ALLOWED=NO \
    CODE_SIGNING_REQUIRED=NO \
    CODE_SIGN_IDENTITY= \
    "${xcode_build_settings[@]}" \
    build 2>&1 | tee "${build_log}"

products_directory="${derived_data}/Build/Products/${configuration}-iphoneos"
app_path=""
for candidate in "${products_directory}"/*.app; do
    [[ -d "${candidate}" ]] || continue
    candidate_bundle_id="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "${candidate}/Info.plist" 2>/dev/null || true)"
    if [[ "${candidate_bundle_id}" == "com.wplatform.mobile" || "${candidate_bundle_id}" == "com.wplatform.mobile.debug" ]]; then
        if [[ -n "${app_path}" ]]; then
            echo "Multiple W Media Player app bundles were produced." >&2
            exit 1
        fi
        app_path="${candidate}"
    fi
done
if [[ -z "${app_path}" ]]; then
    echo "iOS build did not produce a W Media Player app in ${products_directory}." >&2
    exit 1
fi

display_name="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleDisplayName' "${app_path}/Info.plist" 2>/dev/null || true)"
bundle_identifier="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "${app_path}/Info.plist")"
if [[ "${display_name}" != "W Media Player" ]]; then
    echo "Built iOS app has unexpected display name: ${display_name}." >&2
    exit 1
fi

built_version="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "${app_path}/Info.plist")"
if [[ "${built_version}" != "${version}" ]]; then
    echo "Built iOS version ${built_version} does not match ${version}." >&2
    exit 1
fi

executable="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "${app_path}/Info.plist")"
architectures="$(xcrun lipo -archs "${app_path}/${executable}")"
if [[ " ${architectures} " != *" arm64 "* ]]; then
    echo "Built iOS application does not contain arm64." >&2
    exit 1
fi
if ! launch_screen_plist="$(plutil -extract UILaunchScreen xml1 -o - "${app_path}/Info.plist" 2>/dev/null)"; then
    echo "Built iOS application does not contain UILaunchScreen." >&2
    exit 1
fi
if [[ "${launch_screen_plist}" == *"<key>UILaunchScreen</key>"* ]]; then
    echo "Built iOS application contains a nested UILaunchScreen." >&2
    exit 1
fi
if [[ -d "${app_path}/_CodeSignature" ]]; then
    echo "Built iOS application is unexpectedly signed." >&2
    exit 1
fi

widget_path="${app_path}/PlugIns/DownloadsWidgetExtension.appex"
if [[ ! -d "${widget_path}" ]]; then
    echo "Built iOS application does not contain the downloads widget." >&2
    exit 1
fi
widget_executable="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "${widget_path}/Info.plist")"
widget_architectures="$(xcrun lipo -archs "${widget_path}/${widget_executable}")"
if [[ " ${widget_architectures} " != *" arm64 "* ]]; then
    echo "Built downloads widget does not contain arm64." >&2
    exit 1
fi

mkdir -p "${output_directory}"
output_directory="$(cd "${output_directory}" && pwd -P)"
package_root="$(mktemp -d "${TMPDIR:-/tmp}/nuvio-ios-ipa.XXXXXX")"
trap 'rm -rf "${package_root}"; [[ -z "${gradle_init_directory}" ]] || rm -rf "${gradle_init_directory}"' EXIT
mkdir -p "${package_root}/Payload"
ditto "${app_path}" "${package_root}/Payload/$(basename "${app_path}")"

ipa_path="${output_directory}/W-Media-Player-${version}-full-${configuration_slug}.ipa"
temporary_ipa="${package_root}/W-Media-Player-${version}-full-${configuration_slug}.ipa"
(
    cd "${package_root}"
    /usr/bin/zip -qry "${temporary_ipa}" Payload
)
unzip -tq "${temporary_ipa}"
mv "${temporary_ipa}" "${ipa_path}"

# Keep the exact source and Apple toolchain alongside the artifact. Public
# runtime keys and private account data are deliberately excluded.
ipa_name="$(basename "${ipa_path}")"
checksum="$(shasum -a 256 "${ipa_path}" | awk '{print $1}')"
printf '%s  %s\n' "${checksum}" "${ipa_name}" > "${ipa_path}.sha256"
build_info="${ipa_path%.ipa}-build-info.txt"
{
    printf 'Application: W Media Player\nDistribution: full\nConfiguration: %s\n' "${configuration}"
    printf 'Version: %s\nBundle identifier: %s\n' "${built_version}" "${bundle_identifier}"
    printf 'Source commit: %s\n' "$(git rev-parse HEAD)"
    if [[ -n "$(git status --porcelain --untracked-files=no)" ]]; then
        printf 'Source status: contains local tracked changes\n'
    else
        printf 'Source status: clean tracked files\n'
    fi
    printf 'MPVKit commit: %s\n' "$(git -C MPVKit rev-parse HEAD)"
    printf 'Application architectures: %s\nWidget architectures: %s\n' "${architectures}" "${widget_architectures}"
    printf 'iOS SDK: %s\n' "$(xcrun --sdk iphoneos --show-sdk-version)"
    xcodebuild -version
    printf 'Signing: unsigned\nSHA-256: %s\n' "${checksum}"
} > "${build_info}"

echo "Created ${ipa_path}"
