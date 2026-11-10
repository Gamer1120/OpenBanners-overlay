#!/usr/bin/env python3
"""Nightly promotion of the newest tested beta (open testing) release to production on Google Play.

See docs/google-play.md ("Nightly production promotion") for the full flow. Short version:

  * Scheduled runs only act between 00:00 and 05:59 Europe/Amsterdam; outside that they are no-ops.
  * Cutoff = 00:00 Europe/Amsterdam of the current Amsterdam date (for dry runs during the day: of the coming
    night). A tag vN is a candidate when N > production, its commit is older than the cutoff, N <= the newest
    versionCode published on beta, the bundle is in Play's library and there is no hold-vN tag.
  * The newest candidate's existing bundle is put on production (status completed) in one edit, with the
    changelogs of all versions since production as release notes. Production already >= candidate: no-op.
  * Commits use changesInReviewBehavior=ERROR_IF_IN_REVIEW, so nothing in review is ever cancelled.

Modes:
  scheduled    (default) what the cron job runs.
  dry-run      print the decision and delete the edit without committing.
  promote-now  ignore cutoff, window and pause; promote the newest beta version now (or --version N).
Exit codes: 0 done/no-op, 1 error, 2 blocked by changes in review (retry later).
"""

import argparse
import datetime as dt
import os
import re
import subprocess
import sys
from zoneinfo import ZoneInfo

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

AMS = ZoneInfo("Europe/Amsterdam")
WINDOW_END_HOUR = 6  # acting window 00:00-05:59 local time
NOTES_LIMIT = 500    # Play: max 500 characters of release notes per language
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CHANGELOG_ROOT = os.path.join(REPO_ROOT, "fastlane", "metadata", "android")
PUBLISHED = "RELEASE_LIFECYCLE_STATE_PUBLISHED"


# --- pure decision logic (unit-tested in test_play_promote.py) ---------------------------------------------------

def in_window(now_utc):
    return now_utc.astimezone(AMS).hour < WINDOW_END_HOUR


def cutoff_for(now_utc):
    """00:00 Europe/Amsterdam of the Amsterdam date of now_utc, as an aware UTC datetime."""
    local = now_utc.astimezone(AMS)
    midnight = dt.datetime(local.year, local.month, local.day, tzinfo=AMS)
    return midnight.astimezone(dt.timezone.utc)


def next_window_start(now_utc):
    """The moment the next acting window begins (now if already inside one)."""
    if in_window(now_utc):
        return now_utc
    local = now_utc.astimezone(AMS)
    tomorrow = local.date() + dt.timedelta(days=1)
    return dt.datetime(tomorrow.year, tomorrow.month, tomorrow.day, tzinfo=AMS).astimezone(dt.timezone.utc)


def decide(prod, beta, tags, bundles, holds, cutoff, ignore_cutoff=False, explicit=None):
    """Pick the version to promote.

    prod: highest versionCode on production (0 if none); beta: highest versionCode published on beta (0 if none)
    tags: {N: commit time (aware datetime)} for every tag vN; bundles: set of versionCodes in Play's library
    holds: set of N with a hold-vN tag; cutoff: aware datetime.
    Returns (candidate or None, {N: reason it was skipped}, list of eligible N).
    """
    skipped = {}
    if explicit is not None:
        n = explicit
        if n <= prod:
            skipped[n] = f"production already has {prod}"
        elif n not in bundles:
            skipped[n] = "bundle not in the Play library"
        if skipped:
            return None, skipped, []
        return n, skipped, [n]
    eligible = []
    for n in sorted(tags):
        if n <= prod:
            continue  # already in production (or older)
        if n in holds:
            skipped[n] = f"hold-v{n} tag"
        elif n > beta:
            skipped[n] = f"not published on beta yet (beta = {beta or 'none'})"
        elif n not in bundles:
            skipped[n] = "bundle not in the Play library"
        elif not ignore_cutoff and not tags[n] < cutoff:
            skipped[n] = f"committed {tags[n].astimezone(AMS):%Y-%m-%d %H:%M %Z}, not before the cutoff"
        else:
            eligible.append(n)
    return (max(eligible) if eligible else None), skipped, eligible


def build_notes(changelogs, prod, candidate, skip=frozenset(), limit=NOTES_LIMIT):
    """Release notes for one language: changelogs of prod < n <= candidate (minus skip), newest first,
    separated by a blank line. Over the limit the oldest are dropped, or the oldest one that still partly
    fits is shortened with an ellipsis."""
    parts = [changelogs[n].strip() for n in sorted(changelogs, reverse=True)
             if prod < n <= candidate and n not in skip and changelogs[n].strip()]
    out = ""
    for part in parts:
        sep = "\n\n" if out else ""
        if len(out) + len(sep) + len(part) <= limit:
            out += sep + part
            continue
        room = limit - len(out) - len(sep) - 1  # 1 for the ellipsis
        if room >= 40 or not out:  # don't add a tiny fragment of an old changelog
            out += sep + part[:max(room, 0)].rstrip() + "…"
        break
    return out


# --- repository ---------------------------------------------------------------------------------------------------

def git(*args):
    return subprocess.run(["git", "-C", REPO_ROOT, *args], check=True, capture_output=True, text=True).stdout


def read_tags():
    """({N: commit time of tag vN}, {N with a hold-vN tag})"""
    tags, holds = {}, set()
    for name in git("tag", "-l").split():
        m = re.fullmatch(r"v(\d+)", name)
        if m:
            ts = int(git("log", "-1", "--format=%ct", f"{name}^{{commit}}").strip())
            tags[int(m.group(1))] = dt.datetime.fromtimestamp(ts, dt.timezone.utc)
        m = re.fullmatch(r"hold-v(\d+)", name)
        if m:
            holds.add(int(m.group(1)))
    return tags, holds


def read_changelogs():
    """{language: {N: text}} from fastlane/metadata/android/<language>/changelogs/<N>.txt"""
    out = {}
    if not os.path.isdir(CHANGELOG_ROOT):
        return out
    for lang in sorted(os.listdir(CHANGELOG_ROOT)):
        d = os.path.join(CHANGELOG_ROOT, lang, "changelogs")
        if not os.path.isdir(d):
            continue
        for f in os.listdir(d):
            m = re.fullmatch(r"(\d+)\.txt", f)
            if m:
                with open(os.path.join(d, f), encoding="utf-8") as fh:
                    out.setdefault(lang, {})[int(m.group(1))] = fh.read()
    return out


# --- main ---------------------------------------------------------------------------------------------------------

def fmt(t):
    return f"{t.astimezone(AMS):%Y-%m-%d %H:%M %Z}"


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--mode", choices=["scheduled", "dry-run", "promote-now"], default="scheduled")
    ap.add_argument("--dry-run", action="store_true", help="same as --mode dry-run")
    ap.add_argument("--version", type=int, help="promote-now/dry-run: this versionCode (bypasses hold and cutoff)")
    ap.add_argument("--now", help="pretend it is this time (ISO 8601, for testing the window/cutoff)")
    args = ap.parse_args(argv)
    mode = "dry-run" if args.dry_run else args.mode
    if args.version is not None and mode == "scheduled":
        ap.error("--version needs --mode promote-now or dry-run")

    import playlib  # imported here so the pure functions above can be tested without it

    now = dt.datetime.fromisoformat(args.now) if args.now else dt.datetime.now(dt.timezone.utc)
    if now.tzinfo is None:
        now = now.replace(tzinfo=AMS)
    paused = os.environ.get("PLAY_PROMOTION_PAUSED", "").strip().lower() == "true"

    def say(line=""):
        print(line, flush=True)

    say(f"Mode: {mode}. Now: {fmt(now)}.")
    if mode == "scheduled":
        if paused:
            say("PLAY_PROMOTION_PAUSED=true: scheduled promotion is paused, nothing to do.")
            playlib.write_summary("## Production promotion\n\nPaused (repo variable `PLAY_PROMOTION_PAUSED=true`).")
            return 0
        if not in_window(now):
            say("Outside the 00:00-05:59 Europe/Amsterdam window: nothing to do (this run is a DST/retry spare).")
            playlib.write_summary("## Production promotion\n\nOutside the 00:00–05:59 Amsterdam window: no-op.")
            return 0
        at = now
    elif mode == "dry-run":
        at = next_window_start(now)
        if paused:
            say("Note: PLAY_PROMOTION_PAUSED=true, so scheduled runs are no-ops right now.")
    else:
        at = now
    ignore_cutoff = mode == "promote-now" or args.version is not None
    cutoff = cutoff_for(at)
    if mode == "dry-run" and at != now:
        say(f"Evaluated as for the next nightly window ({fmt(at)}).")
    say(f"Cutoff: {fmt(cutoff)}" + (" (ignored)" if ignore_cutoff else ""))

    tags, holds = read_tags()
    changelogs = read_changelogs()

    play = playlib.Play()
    edit = play.edit_insert()
    committed = False
    try:
        prod_track = play.track_get(edit, "production")
        beta_track = play.track_get(edit, "beta")
        bundles = set(play.bundles_list(edit))
        # Review states. tracks.releases.list has a small, undocumented quota ("Listing releases quota exceeded"),
        # so only production and beta are asked, and the run carries on without them if the quota is hit.
        summaries, review_state_known = {}, True
        for track in ("production", "beta"):
            try:
                summaries[track] = play.release_summaries(track)
            except playlib.PlayError as e:
                if e.status not in (403, 429):
                    raise
                say(f"Warning: release states unavailable ({e}); using the edit's tracks instead.")
                summaries, review_state_known = {"production": [], "beta": []}, False
                break

        prod = max(playlib.track_version_codes(prod_track, ("completed", "inProgress", "halted", "draft"))
                   + playlib.summary_version_codes(summaries["production"], (
                       PUBLISHED, playlib.IN_REVIEW, "RELEASE_LIFECYCLE_STATE_APPROVED_NOT_PUBLISHED")), default=0)
        beta_edit = max(playlib.track_version_codes(beta_track), default=0)
        if review_state_known:
            beta = max(playlib.summary_version_codes(summaries["beta"], (PUBLISHED,)), default=0)
        else:
            beta = beta_edit  # may include a release still in review; the commit then fails with exit 2

        say(f"Production: {prod or 'none'}. Beta published: {beta or 'none'} (beta track: {beta_edit or 'none'})."
            + ("" if review_state_known else " Review state unknown."))
        states = "; ".join(playlib.describe_summaries(summaries)) if review_state_known else "unknown (quota)"
        say("Play release states: " + states)
        say(f"Bundles in library: {', '.join(map(str, sorted(bundles)))}")
        say("Hold tags: " + (", ".join(f"hold-v{n}" for n in sorted(holds)) or "none"))
        reviews = playlib.in_review(summaries)

        candidate, skipped, eligible = decide(prod, beta, tags, bundles, holds, cutoff,
                                              ignore_cutoff=ignore_cutoff, explicit=args.version)
        for n in sorted(skipped):
            say(f"  v{n}: skipped: {skipped[n]}")
        say(f"Candidates: {', '.join(f'v{n}' for n in eligible) or 'none'}")

        md = [f"## Production promotion ({mode})", "",
              f"- Production: **{prod or 'none'}**, beta published: **{beta or 'none'}** (beta track: {beta_edit or 'none'})",
              f"- Cutoff: {fmt(cutoff)}" + (" (ignored)" if ignore_cutoff else ""),
              f"- Candidates: {', '.join(f'v{n}' for n in eligible) or 'none'}"]
        md += [f"- v{n} skipped: {why}" for n, why in sorted(skipped.items())]
        md += ["- Play release states: " + states]

        if candidate is None:
            say("Decision: nothing to promote.")
            md.append("\n**Decision: nothing to promote.**")
            playlib.write_summary("\n".join(md))
            return 0

        if candidate not in tags:
            say(f"Warning: v{candidate} has no git tag in this checkout.")
        if candidate > beta:
            say(f"Warning: v{candidate} is not published on beta (explicit version).")
        notes = {}
        for lang, logs in changelogs.items():
            text = build_notes(logs, prod, candidate, skip=holds - {candidate})
            if text:
                notes[lang] = text
        if not notes:
            notes["en-US"] = f"Version {candidate}"
        release = {"name": str(candidate), "versionCodes": [str(candidate)], "status": "completed",
                   "releaseNotes": [{"language": lang, "text": text} for lang, text in sorted(notes.items())]}
        say(f"Decision: promote v{candidate} to production (status completed, existing bundle, no rebuild).")
        for lang, text in sorted(notes.items()):
            say(f"Release notes [{lang}] ({len(text)} chars):")
            for line in text.splitlines():
                say(f"    {line}")
        md.append(f"\n**Decision: promote v{candidate} to production.**\n")
        for lang, text in sorted(notes.items()):
            md.append(f"Release notes {lang} ({len(text)} chars):\n\n~~~\n{text}\n~~~")

        if reviews:
            what = ", ".join(f"{t} {name or codes}" for t, name, codes in reviews)
            if mode == "dry-run":
                say(f"Note: in review right now: {what}. A real run would stop here and retry next hour.")
                md.append(f"\nIn review right now: {what} (a real run would wait and retry).")
            else:
                msg = (f"Changes are in review on Google Play ({what}). Not committing, so that review is not "
                       "cancelled. The next hourly run in tonight's window retries.")
                say(msg)
                md.append(f"\n:warning: **Blocked:** {msg}")
                playlib.write_summary("\n".join(md))
                return 2

        play.track_update(edit, "production", [release])
        if mode == "dry-run":
            say("Dry run: Play accepted the production track update in the edit; deleting the edit, nothing committed.")
            md.append("\nDry run: edit validated by Play and deleted, nothing committed.")
            playlib.write_summary("\n".join(md))
            return 0
        try:
            play.edit_commit(edit, playlib.ERROR_IF_IN_REVIEW)
            committed = True
        except playlib.PlayError as e:
            if e.changes_in_review:
                msg = ("Google Play refused the commit: changes are already in review. Nothing was cancelled. "
                       "The next hourly run retries.")
                say(msg)
                md.append(f"\n:warning: **Blocked:** {msg}")
                playlib.write_summary("\n".join(md))
                return 2
            raise
        say(f"Committed: production = v{candidate}.")
        md.append(f"\n:white_check_mark: Committed: production = v{candidate} (Google Play may review it first).")
        playlib.write_summary("\n".join(md))
        return 0
    finally:
        if not committed:
            play.edit_delete(edit)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:  # noqa: BLE001 - one clear line in the Actions log
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)
