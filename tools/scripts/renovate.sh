#!/usr/bin/env bash
# Runs the pinned Renovate container (same image as .github/workflows/renovate.yml).
#   renovate.sh validate   validate .renovaterc.jsonc and the bot configuration (offline)
#                          (needs RENOVATE_TOKEN with read access)
#   renovate.sh run        real run with the bot configuration (needs RENOVATE_TOKEN with the
#                          permissions documented in docs/renovate.md)
source "$(dirname "$0")/lib.sh"
cd "${ROOT}"

IMAGE="ghcr.io/renovatebot/renovate:44.145.1-full@sha256:2ead179919ff9cf3a2486a8667b26446138d1b9329823f674543a493afc3e1e4"
command -v docker >/dev/null || die "docker is required for Renovate tasks"

run_image() {
  docker run --rm -t --init \
    -e LOG_LEVEL="${LOG_LEVEL:-info}" \
    -v "${ROOT}:/usr/src/app:ro" -w /usr/src/app \
    "$@"
}

case "${1:-}" in
  validate)
    run_image --entrypoint renovate-config-validator "${IMAGE}" --strict .renovaterc.jsonc
    run_image -e RENOVATE_CONFIG_FILE=/usr/src/app/.github/renovate/bot-config.json5 \
      --entrypoint renovate-config-validator "${IMAGE}" --strict
    ;;
  dry-run | run)
    : "${RENOVATE_TOKEN:?set RENOVATE_TOKEN (see docs/renovate.md)}"
    mode=()
    [[ "$1" == "dry-run" ]] && mode=(-e RENOVATE_DRY_RUN=lookup)
    docker run --rm -t --init -e LOG_LEVEL="${LOG_LEVEL:-info}" \
      -e RENOVATE_TOKEN -e RENOVATE_CONFIG_FILE=/config/bot-config.json5 \
      -e RENOVATE_GIT_AUTHOR "${mode[@]}" \
      -v "${ROOT}/.github/renovate/bot-config.json5:/config/bot-config.json5:ro" \
      "${IMAGE}"
    ;;
  *) die "usage: renovate.sh validate | dry-run | run" ;;
esac
