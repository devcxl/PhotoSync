#!/usr/bin/env bash
#
# Release guard for the on-demand AI model catalog.
#
# The manifest between CATALOG-BEGIN and CATALOG-END is the single source the JVM test
# `AiModelCatalogTest` compares with `AiModelCatalog.kt`; keep both sides byte-for-byte in sync.
# For every downloadable entry this script:
#   1. sends a HEAD request and requires a 2xx status;
#   2. sends `Range: bytes=0-65535` and requires a 206 (or a 200 that advertises
#      `Accept-Ranges: bytes`), proving the mirror can resume;
#   3. fully downloads at least one source and compares byte count and SHA-256.
# Any model with no usable source exits non-zero, so a pin that points at an empty release
# (the MODNet `ai-models-v1` 404 regression) fails CI instead of a user's device.
#
# Requirements: bash, curl, sha256sum (coreutils) or shasum (Perl), wc.
#
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROBE_BYTES=65536
MAX_PROBE_BYTES=2097152
MAX_HEAD_SECONDS=45
MAX_RANGE_SECONDS=60
MAX_FULL_SECONDS=900

TMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/verify-ai-model-catalog.XXXXXX")
cleanup() {
    rm -rf "$TMP_DIR"
}
trap cleanup EXIT

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    else
        echo "sha256sum or shasum is required" >&2
        exit 2
    fi
}

file_size() {
    wc -c <"$1" | tr -d '[:space:]'
}

failures=0
checked_models=0

fail() {
    printf '  FAIL  %s\n' "$*" >&2
    failures=$((failures + 1))
}

# Returns 0 when the mirror answers HEAD with 2xx and honours a bounded Range request.
probe_mirror() {
    local url=$1
    local label=$2
    local head_code range_code range_size headers

    head_code=$(curl --silent --show-error --location --head \
        --max-time "$MAX_HEAD_SECONDS" \
        --output /dev/null --write-out '%{http_code}' "$url" 2>/dev/null || true)
    if [[ ! "$head_code" =~ ^2[0-9][0-9]$ ]]; then
        printf '  HEAD  %s -> %s\n' "$label" "${head_code:-transport-error}" >&2
        return 1
    fi

    headers="$TMP_DIR/range.headers"
    range_size=$(curl --silent --show-error --location \
        --max-time "$MAX_RANGE_SECONDS" \
        --range "0-$((PROBE_BYTES - 1))" \
        --max-filesize "$MAX_PROBE_BYTES" \
        --dump-header "$headers" \
        --output "$TMP_DIR/range.bin" \
        --write-out '%{http_code}|%{size_download}' "$url" 2>/dev/null || true)
    range_code=${range_size%%|*}
    range_size=${range_size##*|}

    if [[ "$range_code" != "206" ]] && ! grep -qi '^accept-ranges:[[:space:]]*bytes' "$headers"; then
        printf '  RANGE %s -> HTTP %s without byte ranges\n' "$label" "${range_code:-transport-error}" >&2
        return 1
    fi
    if [[ -z "$range_size" || "$range_size" == "0" ]]; then
        printf '  RANGE %s -> empty probe body\n' "$label" >&2
        return 1
    fi
    printf '  probe %s -> HEAD %s, range %s (%s bytes)\n' \
        "$label" "$head_code" "$range_code" "$range_size"
    return 0
}

# Downloads one mirror completely and checks both pins; echoes "ok" on success.
verify_full_download() {
    local url=$1
    local label=$2
    local expected_size=$3
    local expected_sha=$4
    local target="$TMP_DIR/model.bin"

    if ! curl --silent --show-error --fail --location \
        --max-time "$MAX_FULL_SECONDS" \
        --output "$target" "$url"; then
        fail "$label: full download failed ($url)"
        return 1
    fi
    local actual_size actual_sha
    actual_size=$(file_size "$target")
    if [[ "$actual_size" != "$expected_size" ]]; then
        fail "$label: size $actual_size != pinned $expected_size ($url)"
        rm -f "$target"
        return 1
    fi
    actual_sha=$(sha256_of "$target")
    if [[ "$actual_sha" != "$expected_sha" ]]; then
        fail "$label: SHA-256 $actual_sha != pinned $expected_sha ($url)"
        rm -f "$target"
        return 1
    fi
    printf '  full  %s -> %s bytes, SHA-256 ok\n' "$label" "$actual_size"
    rm -f "$target"
    return 0
}

# key|id|fileName|version|byteSize|sha256|url1|url2|url3
while IFS='|' read -r key id file version size sha url1 url2 url3; do
    [[ -z "${key:-}" || "$key" == \#* ]] && continue
    [[ "$key" != "CATALOG-BEGIN" && "$key" != "CATALOG-END" ]] || continue

    checked_models=$((checked_models + 1))
    urls=()
    for candidate in "${url1:-}" "${url2:-}" "${url3:-}"; do
        [[ -n "$candidate" ]] && urls+=("$candidate")
    done

    printf '\n[%s] %s (%s bytes)\n' "$key" "$id" "$size"
    if [[ ${#urls[@]} -eq 0 ]]; then
        printf '  read-only entry: no download mirrors, skipped by the network guard\n'
        continue
    fi

    usable=()
    for url in "${urls[@]}"; do
        if probe_mirror "$url" "$key"; then
            usable+=("$url")
        else
            fail "$key: mirror failed the HEAD/Range probe ($url)"
        fi
    done

    model_ok=0
    for url in "${usable[@]}"; do
        if verify_full_download "$url" "$key" "$size" "$sha"; then
            model_ok=1
            break
        fi
    done

    if [[ "$model_ok" -ne 1 ]]; then
        fail "$key: every mirror is unreachable or fails size/SHA-256 verification"
    fi
done <<'CATALOG'
# CATALOG-BEGIN
MODNET_PORTRAIT|modnet-portrait-512|modnet_portrait_512_v1.onnx|1|25888640|07c308cf0fc7e6e8b2065a12ed7fc07e1de8febb7dc7839d7b7f15dd66584df9|https://huggingface.co/Xenova/modnet/resolve/main/onnx/model.onnx|https://hf-mirror.com/Xenova/modnet/resolve/main/onnx/model.onnx
MI_GAN_PIPELINE|migan-pipeline-512|migan_pipeline_512_v1.onnx|1|28079181|6f1f3530a1a2324b19752018ce756088b07973cda8d7d890034ace5c8a48c40b|https://huggingface.co/andraniksargsyan/migan/resolve/main/migan_pipeline_v2.onnx|https://hf-mirror.com/andraniksargsyan/migan/resolve/main/migan_pipeline_v2.onnx|https://huggingface.co/anyisalin/migan-onnx/resolve/main/onnx/model.onnx
MI_GAN_GENERATOR_LEGACY|migan-generator-512|migan_generator_512_v1.onnx|1|28026303|522048b4fc3fae7adff3af48859cd8d19412d02a8a9420614f3d4474a0901bfe
# CATALOG-END
CATALOG

printf '\nChecked %d catalog entries, %d failure(s).\n' "$checked_models" "$failures"
if [[ "$failures" -ne 0 ]]; then
    exit 1
fi
printf 'AI model catalog guard passed.\n'
