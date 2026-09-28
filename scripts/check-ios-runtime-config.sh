#!/usr/bin/env bash

# This value becomes public inside the IPA. Never print it, even on failure.
set +x
set -euo pipefail

key="${NUVIO_SUPABASE_ANON_KEY:-}"
if [[ -z "${key}" ]]; then
    echo "Set NUVIO_SUPABASE_ANON_KEY in the Codemagic application group w_media_public." >&2
    exit 1
fi
if [[ "${key}" == sb_secret_* ]]; then
    echo "A Supabase server secret cannot be embedded in an iPhone app. Use the project's publishable key." >&2
    exit 1
fi

if [[ "${key}" =~ ^sb_publishable_[A-Za-z0-9_-]+$ ]]; then
    echo "Public Supabase key format verified. Device sign-in still needs verification."
    exit 0
fi

# Legacy anon keys are JWTs; service_role JWTs must never reach the app bundle.
if [[ ! "${key}" =~ ^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$ ]]; then
    echo "NUVIO_SUPABASE_ANON_KEY must be a publishable key or a legacy anon JWT." >&2
    exit 1
fi
payload="${key#*.}"
payload="${payload%%.*}"
case "$((${#payload} % 4))" in
    0) ;;
    2) payload+="==" ;;
    3) payload+="=" ;;
    *) echo "The legacy Supabase key has an invalid JWT payload." >&2; exit 1 ;;
esac
if ! decoded="$(printf '%s' "${payload}" | tr '_-' '/+' | /usr/bin/base64 -D 2>/dev/null)"; then
    echo "The legacy Supabase key has an invalid JWT payload." >&2
    exit 1
fi
if ! role="$(printf '%s' "${decoded}" | /usr/bin/plutil -extract role raw -o - - 2>/dev/null)" ||
   [[ "${role}" != "anon" ]]; then
    echo "Only a legacy anon JWT may be embedded in an iPhone app; server and user JWTs are rejected." >&2
    exit 1
fi
echo "Legacy public Supabase key format verified. Device sign-in still needs verification."
