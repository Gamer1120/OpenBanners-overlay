#!/usr/bin/env python3
"""Upload a signed AAB to a Google Play track (used by the Release workflow for tag pushes).

Creates an edit, uploads the bundle, puts it on the track with the changelog as release notes and commits with
changesInReviewBehavior=ERROR_IF_IN_REVIEW: if anything is in review (for example a production release from the
nightly promotion) the commit is refused and nothing is cancelled. Exit code 2 means exactly that; re-run the
failed job once the review is done. --cancel-review deliberately cancels the review and resubmits everything.

  python3 scripts/play_upload.py --aab app.aab --track beta [--status completed] [--dry-run]
"""

import argparse
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import playlib  # noqa: E402

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NOTES_LIMIT = 500


def release_notes(version_code):
    """[{language, text}] from fastlane/metadata/android/<language>/changelogs/<versionCode>.txt"""
    root = os.path.join(REPO_ROOT, "fastlane", "metadata", "android")
    notes = []
    for lang in sorted(os.listdir(root)) if os.path.isdir(root) else []:
        path = os.path.join(root, lang, "changelogs", f"{version_code}.txt")
        if os.path.isfile(path):
            with open(path, encoding="utf-8") as f:
                text = f.read().strip()
            if len(text) > NOTES_LIMIT:
                text = text[:NOTES_LIMIT - 1].rstrip() + "…"
            if text:
                notes.append({"language": lang, "text": text})
    return notes or [{"language": "en-US", "text": f"Version {version_code}"}]


def gradle_version_code():
    with open(os.path.join(REPO_ROOT, "app", "build.gradle")) as f:
        return int(re.search(r"versionCode\s+(\d+)", f.read()).group(1))


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--aab", required=True)
    ap.add_argument("--track", default="beta", choices=["beta", "alpha", "internal"])
    ap.add_argument("--status", default="completed", choices=["completed", "draft"])
    ap.add_argument("--cancel-review", action="store_true",
                    help="cancel changes that are in review and resubmit everything (CANCEL_IN_REVIEW_AND_SUBMIT)")
    ap.add_argument("--dry-run", action="store_true", help="do everything except the commit; delete the edit")
    args = ap.parse_args(argv)

    expected = gradle_version_code()
    play = playlib.Play()
    edit = play.edit_insert()
    committed = False
    try:
        try:
            version_code = play.bundle_upload(edit, args.aab)
            print(f"Uploaded bundle: versionCode {version_code}.")
        except playlib.PlayError as e:
            # A re-run after a refused commit, or after a manual upload: the bundle is already in Play's library.
            if "already been used" not in str(e.message) or expected not in play.bundles_list(edit):
                raise
            version_code = expected
            print(f"Bundle {version_code} is already in Play's library; using it as it is.")
        if version_code != expected:
            raise SystemExit(f"AAB versionCode {version_code} != app/build.gradle versionCode {expected}")
        notes = release_notes(version_code)
        play.track_update(edit, args.track, [{"name": str(version_code), "versionCodes": [str(version_code)],
                                              "status": args.status, "releaseNotes": notes}])
        print(f"Track {args.track}: v{version_code}, status {args.status}. Notes: {notes[0]['text']!r}")
        if args.dry_run:
            print("Dry run: deleting the edit, nothing committed.")
            return 0
        behavior = playlib.CANCEL_IN_REVIEW_AND_SUBMIT if args.cancel_review else playlib.ERROR_IF_IN_REVIEW
        try:
            play.edit_commit(edit, behavior)
            committed = True
        except playlib.PlayError as e:
            if e.changes_in_review:
                msg = ("Google Play refused the upload: other changes are in review (often a production release "
                       "from the nightly promotion). Nothing was cancelled. The GitHub release is published; re-run "
                       "this failed job once that review is finished (gh run rerun <run-id> --failed), or run the "
                       "Release workflow on the tag with cancel_review=true to cancel that review on purpose.")
                print(f"::error::{msg}")
                playlib.write_summary(f"## Google Play upload\n\n:warning: {msg}")
                return 2
            raise
        print(f"Committed: {args.track} = v{version_code} ({behavior}).")
        playlib.write_summary(f"## Google Play upload\n\n:white_check_mark: {args.track} = v{version_code}, "
                              f"status {args.status}.")
        return 0
    finally:
        if not committed:
            play.edit_delete(edit)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:  # noqa: BLE001
        print(f"::error::{e}")
        sys.exit(1)
