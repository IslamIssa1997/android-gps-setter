#!/usr/bin/env bash
#
# Prepend a new release section to CHANGELOG.md and emit scoped notes for the GitHub Release body.
#
# Usage: scripts/update-changelog.sh <version> [notes-file]
#   <version>     semver without the leading "v", e.g. 2.1.0
#   [notes-file]  optional file whose contents become the section body. In the release workflow this
#                 holds GitHub's auto-generated release notes. When omitted or empty, the body falls
#                 back to commit subjects since the previous git tag.
#
# Side effects:
#   - CHANGELOG.md gains a "## [<version>] — <date>" section directly above the newest existing one,
#     and a "[<version>]: .../releases/tag/v<version>" reference link at the end of the file.
#   - release_notes.md is written with just this version's body (used as the release description).
#
# Idempotent guard: refuses to run if the version already has a section, so a re-run never duplicates.
set -euo pipefail

VERSION="${1:?usage: update-changelog.sh <version> [notes-file]}"
NOTES_FILE="${2:-}"
REPO="${GITHUB_REPOSITORY:-IslamIssa1997/android-gps-setter}"
DATE="$(date +%Y-%m-%d)"
CHANGELOG="CHANGELOG.md"

if grep -q "^## \[${VERSION}\]" "$CHANGELOG"; then
  echo "CHANGELOG already has a [${VERSION}] section; nothing to do." >&2
  exit 0
fi

# --- Build the section body -------------------------------------------------
BODY=""
if [[ -n "$NOTES_FILE" && -s "$NOTES_FILE" ]]; then
  BODY="$(cat "$NOTES_FILE")"
else
  PREV_TAG="$(git describe --tags --abbrev=0 2>/dev/null || true)"
  if [[ -n "$PREV_TAG" ]]; then
    RANGE="${PREV_TAG}..HEAD"
  else
    RANGE="HEAD"   # no prior tag: use the whole history
  fi
  # One bullet per non-merge commit subject, dropping the changelog-commit noise.
  BODY="$(git log "$RANGE" --no-merges --pretty=format:'- %s' \
            | grep -viE '\[skip ci\]|^- (chore: )?update changelog' || true)"
fi
if [[ -z "${BODY//[[:space:]]/}" ]]; then
  BODY="- Maintenance release."
fi

# --- Assemble the section ---------------------------------------------------
SECTION_FILE="$(mktemp)"
trap 'rm -f "$SECTION_FILE"' EXIT
{
  echo "## [${VERSION}] — ${DATE}"
  echo
  echo "$BODY"
} > "$SECTION_FILE"

# Scoped notes for the release body = the body without the heading line.
printf '%s\n' "$BODY" > release_notes.md

# --- Insert above the newest existing "## [" section ------------------------
awk -v secfile="$SECTION_FILE" '
  function printsec(   line) { while ((getline line < secfile) > 0) print line }
  /^## \[/ && !done { printsec(); print ""; done = 1 }
  { print }
  END { if (!done) { print ""; printsec() } }
' "$CHANGELOG" > "${CHANGELOG}.tmp"
mv "${CHANGELOG}.tmp" "$CHANGELOG"

# --- Append the reference link ---------------------------------------------
printf '[%s]: https://github.com/%s/releases/tag/v%s\n' "$VERSION" "$REPO" "$VERSION" >> "$CHANGELOG"

echo "Added [${VERSION}] — ${DATE} to $CHANGELOG and wrote release_notes.md" >&2
