#!/usr/bin/env python3
"""Push the Google Play store listing texts from fastlane/metadata/android/<language>/ to Play.

Every language folder with title.txt, short_description.txt and full_description.txt becomes the Play listing for
that language (folder name = Play language code, e.g. en-US, nl-NL, pt-BR, uk, id). Languages that are on Play but
have no folder are left alone (never deleted). Graphics are not touched; an existing promo video URL is kept.

  python3 scripts/play_listing.py --check     # read-only: compare the live listings with the repo (edit deleted)
  python3 scripts/play_listing.py --dry-run   # upload into a throwaway edit, let Play validate it, delete the edit
  python3 scripts/play_listing.py             # upload and commit (goes to Play's review)

Commits with changesInReviewBehavior=ERROR_IF_IN_REVIEW: if anything is in review (a beta/production release, an
earlier listing change), Play refuses the commit, nothing is cancelled and the script exits with code 2. Run it
again once that review is done. Credentials: see playlib.py (never printed).
"""

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import playlib  # noqa: E402

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
METADATA = os.path.join(REPO_ROOT, "fastlane", "metadata", "android")
LIMITS = {"title": 30, "shortDescription": 80, "fullDescription": 4000}
FILES = {"title": "title.txt", "shortDescription": "short_description.txt", "fullDescription": "full_description.txt"}


def read_repo_listings(root=METADATA):
    """{language: {title, shortDescription, fullDescription}} from the fastlane folders."""
    out = {}
    for lang in sorted(os.listdir(root)):
        d = os.path.join(root, lang)
        if not os.path.isdir(d) or not all(os.path.isfile(os.path.join(d, f)) for f in FILES.values()):
            continue
        listing = {}
        for field, name in FILES.items():
            with open(os.path.join(d, name), encoding="utf-8") as f:
                listing[field] = f.read().strip()
        out[lang] = listing
    return out


def problems(listings):
    errs = []
    for lang, listing in listings.items():
        for field, limit in LIMITS.items():
            n = len(listing[field])  # Unicode code points
            if not n:
                errs.append(f"{lang}: {field} is empty")
            elif n > limit:
                errs.append(f"{lang}: {field} has {n} characters (max {limit})")
    return errs


def live_listings(play, edit_id):
    res = play._call("GET", f"{playlib.API}/{play.package}/edits/{edit_id}/listings", "edits.listings.list")
    return {item["language"]: item for item in res.get("listings", []) or []}


def diff(repo, live):
    """Lines describing where the live listings differ from the repo (empty = all match)."""
    out = []
    for lang, want in repo.items():
        have = live.get(lang)
        if not have:
            out.append(f"{lang}: not on Play")
            continue
        for field in FILES:
            if (have.get(field) or "").strip() != want[field]:
                out.append(f"{lang}: {field} differs")
    for lang in sorted(set(live) - set(repo)):
        out.append(f"{lang}: on Play but not in the repo (left alone)")
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = ap.add_mutually_exclusive_group()
    mode.add_argument("--dry-run", action="store_true", help="validate in a throwaway edit, commit nothing")
    mode.add_argument("--check", action="store_true", help="only compare the live listings with the repo")
    ap.add_argument("--language", action="append", help="only this language (repeatable)")
    args = ap.parse_args(argv)

    repo = read_repo_listings()
    if args.language:
        missing = [lang for lang in args.language if lang not in repo]
        if missing:
            raise SystemExit(f"No complete fastlane listing for: {', '.join(missing)}")
        repo = {lang: repo[lang] for lang in args.language}
    errs = problems(repo)
    if errs:
        raise SystemExit("Listing texts are invalid:\n  " + "\n  ".join(errs))
    print(f"Repo listings: {', '.join(repo)} ({len(repo)} languages)")

    play = playlib.Play()
    edit_id = play.edit_insert()
    committed = False
    try:
        live = live_listings(play, edit_id)
        if args.check:
            d = diff(repo, live)
            print("\n".join(d) if d else "Live listings match the repo.")
            return 1 if any("left alone" not in x for x in d) else 0

        todo = [lang for lang in repo if diff({lang: repo[lang]}, live)]
        if not todo:
            print("Nothing to do: the live listings already match the repo.")
            return 0
        for lang in todo:
            body = {"language": lang, **repo[lang]}
            video = (live.get(lang) or {}).get("video")
            if video:
                body["video"] = video
            play._call("PUT", f"{playlib.API}/{play.package}/edits/{edit_id}/listings/{lang}",
                       f"edits.listings.update {lang}", json_body=body)
            print(f"  {lang}: {'updated' if lang in live else 'added'}")

        d = [x for x in diff(repo, live_listings(play, edit_id)) if "left alone" not in x]
        if d:
            raise SystemExit("Play stored something else than was sent:\n  " + "\n  ".join(d))

        try:
            if args.dry_run:
                play._call("POST", f"{playlib.API}/{play.package}/edits/{edit_id}:validate", "edits.validate")
                print(f"Dry run: Play accepted {len(todo)} listing(s); edit deleted, nothing committed.")
                return 0
            play.edit_commit(edit_id)  # ERROR_IF_IN_REVIEW: never cancels a running review
        except playlib.PlayError as e:
            if e.status == 403:
                print(f"Refused by Play ({e}). The listings were accepted into the edit, but the service account may "
                      "not publish store listing changes: in Play Console → Users and permissions, give it the app "
                      "permission 'Edit store listing, pricing & distribution'. Nothing was committed.",
                      file=sys.stderr)
                return 3
            if e.changes_in_review:
                print("Not committed: other changes are in review on Play. Nothing was cancelled; run this again "
                      "once that review is done.", file=sys.stderr)
                return 2
            raise
        committed = True
        print(f"Committed {len(todo)} listing(s); Play reviews the change before it goes live.")
        return 0
    finally:
        if not committed:
            play.edit_delete(edit_id)


if __name__ == "__main__":
    sys.exit(main())
