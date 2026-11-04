#!/usr/bin/env bash
# Decide Cloudflare Pages / GitHub Pages targets for the GenHexBook workflow.
# Prints no secret values. Writes step outputs to $GITHUB_OUTPUT.
#
# Inputs (environment):
#   INPUT_DEPLOY   workflow_dispatch deploy choice, or empty
#   INPUT_SITE     workflow_dispatch site URL override, or empty
#   INPUT_PUB      workflow_dispatch publisher: auto | actions | peaceiris
#   VAR_DEPLOY     repository variable GENHEXBOOK_DEPLOY
#   VAR_SITE       repository variable GENHEXBOOK_SITE_URL
#   VAR_PROJECT    repository variable GENHEXBOOK_CF_PROJECT
#   VAR_GH         repository variable GENHEXBOOK_GITHUB_PAGES (true | false | empty)
#   VAR_PUB        repository variable GENHEXBOOK_GH_PUBLISHER (actions | peaceiris)
#   CF_TOKEN       CLOUDFLARE_API_TOKEN (presence only)
#   CF_ACCOUNT     CLOUDFLARE_ACCOUNT_ID (presence only)
#   GITHUB_TOKEN   used to probe the Pages API; never printed
#   GITHUB_REPOSITORY
set -euo pipefail

deploy="${INPUT_DEPLOY:-}"
if [ -z "$deploy" ]; then
  deploy="${VAR_DEPLOY:-auto}"
fi
case "$deploy" in
  auto|both|cloudflare|github|none) ;;
  *) echo "::error::GENHEXBOOK_DEPLOY must be auto, both, cloudflare, github, or none (got ${deploy})" >&2; exit 1 ;;
esac

publisher="${INPUT_PUB:-}"
if [ -z "$publisher" ] || [ "$publisher" = "auto" ]; then
  publisher="${VAR_PUB:-actions}"
fi
case "$publisher" in
  actions|peaceiris) ;;
  *) echo "::error::GENHEXBOOK_GH_PUBLISHER must be actions or peaceiris (got ${publisher})" >&2; exit 1 ;;
esac

site="${INPUT_SITE:-}"
if [ -z "$site" ]; then
  site="${VAR_SITE:-}"
fi
if [ -z "$site" ]; then
  site="https://mielib-hexbook.pages.dev"
fi
site="${site%/}"
# Letters, digits, and the URL punctuation hexdoc/Pages actually use. Rejects
# spaces, quotes, and shell metacharacters so the value can be passed around.
if ! printf '%s' "$site" | grep -Eq '^https?://[A-Za-z0-9._~:/?#&=%+-]+$'; then
  echo "::error::GENHEXBOOK_SITE_URL must be an http(s) URL without spaces or quotes" >&2
  exit 1
fi

project="${VAR_PROJECT:-mielib-hexbook}"
if ! [[ "$project" =~ ^[A-Za-z0-9][A-Za-z0-9_-]{0,57}$ ]]; then
  echo "::error::GENHEXBOOK_CF_PROJECT must be a Pages project name (letters, digits, _ or -)" >&2
  exit 1
fi

cf_ready=false
if [ -n "${CF_TOKEN:-}" ] && [ -n "${CF_ACCOUNT:-}" ]; then
  cf_ready=true
fi

pages_code="skip"
pages_ready=false
if [ -n "${GITHUB_TOKEN:-}" ] && [ -n "${GITHUB_REPOSITORY:-}" ]; then
  pages_code="$(curl -sS -o /tmp/genhexbook-pages.json -w "%{http_code}" \
    -H "Authorization: Bearer ${GITHUB_TOKEN}" \
    -H "Accept: application/vnd.github+json" \
    -H "X-GitHub-Api-Version: 2022-11-28" \
    "https://api.github.com/repos/${GITHUB_REPOSITORY}/pages" || echo "000")"
  if [ "$pages_code" = "200" ]; then
    pages_ready=true
  fi
fi
rm -f /tmp/genhexbook-pages.json
echo "GitHub Pages API status: ${pages_code}"

want_cf=no
want_gh=no
case "$deploy" in
  auto) want_cf=auto; want_gh=auto ;;
  both) want_cf=yes; want_gh=yes ;;
  cloudflare) want_cf=yes; want_gh=no ;;
  github) want_cf=no; want_gh=yes ;;
  none) want_cf=no; want_gh=no ;;
esac

deploy_cf=false
deploy_gh_actions=false
deploy_gh_branch=false

if [ "$want_cf" = "yes" ]; then
  if [ "$cf_ready" != true ]; then
    echo "::error::Cloudflare deploy requested but CLOUDFLARE_API_TOKEN or CLOUDFLARE_ACCOUNT_ID is unset." >&2
    exit 1
  fi
  deploy_cf=true
elif [ "$want_cf" = "auto" ] && [ "$cf_ready" = true ]; then
  deploy_cf=true
elif [ "$want_cf" = "auto" ]; then
  echo "::notice::Skipping Cloudflare Pages (CLOUDFLARE_API_TOKEN or CLOUDFLARE_ACCOUNT_ID is unset)."
fi

if [ "$want_gh" = "auto" ] && [ "${VAR_GH:-}" = "false" ]; then
  echo "::notice::Skipping GitHub Pages (GENHEXBOOK_GITHUB_PAGES=false)."
  want_gh=no
fi

if [ "$want_gh" != "no" ]; then
  if [ "$publisher" = "peaceiris" ]; then
    if [ "$want_gh" = "yes" ] || [ "${VAR_GH:-}" = "true" ] || [ "$pages_ready" = true ]; then
      deploy_gh_branch=true
    else
      echo "::notice::Skipping GitHub Pages. Set GENHEXBOOK_GITHUB_PAGES=true to publish the gh-pages branch with peaceiris/actions-gh-pages, or switch GENHEXBOOK_GH_PUBLISHER to actions after enabling Pages (Source: GitHub Actions)."
    fi
  else
    if [ "$pages_ready" = true ]; then
      deploy_gh_actions=true
    elif [ "$want_gh" = "yes" ] || [ "${VAR_GH:-}" = "true" ]; then
      echo "::error::GitHub Pages deploy requested but no Pages site exists (API ${pages_code}). Settings → Pages → Source: GitHub Actions. Or set GENHEXBOOK_GH_PUBLISHER=peaceiris." >&2
      exit 1
    else
      echo "::notice::Skipping GitHub Pages (API ${pages_code}). Enable Pages with source GitHub Actions, or set GENHEXBOOK_GH_PUBLISHER=peaceiris and GENHEXBOOK_GITHUB_PAGES=true."
    fi
  fi
fi

out="${GITHUB_OUTPUT:-/dev/stdout}"
{
  echo "deploy_cf=${deploy_cf}"
  echo "deploy_gh_actions=${deploy_gh_actions}"
  echo "deploy_gh_branch=${deploy_gh_branch}"
  echo "site_url=${site}"
  echo "cf_project=${project}"
} >>"$out"

echo "deploy mode=${deploy} cloudflare=${deploy_cf} github_actions=${deploy_gh_actions} github_branch=${deploy_gh_branch} publisher=${publisher}"
echo "site_url=${site} cf_project=${project}"

if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  cat >>"$GITHUB_STEP_SUMMARY" <<EOF
### GenHexBook deploy

| | |
|---|---|
| mode | \`${deploy}\` |
| site URL baked into the book | ${site} |
| Cloudflare Pages | ${deploy_cf} (project \`${project}\`) |
| GitHub Pages (\`actions/deploy-pages\`) | ${deploy_gh_actions} |
| GitHub Pages (\`peaceiris/actions-gh-pages\`) | ${deploy_gh_branch} |
EOF
fi
