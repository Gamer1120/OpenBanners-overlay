"""Minimal Google Play Developer API (androidpublisher v3) client, standard library only.

The service-account JWT is signed with the openssl command line tool, so neither google-auth nor requests is
needed (works the same on GitHub runners and locally).

Credentials, first match wins:
  * PLAY_SERVICE_ACCOUNT_JSON  - the JSON key itself (GitHub secret)
  * PLAY_SERVICE_ACCOUNT_FILE  - path to the JSON key
  * ~/.config/openbanners-signing/play-service-account.json
Never print the key or the access token.
"""

import base64
import json
import os
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

PACKAGE = "org.openbanners.overlay"
API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications"
UPLOAD_API = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"
DEFAULT_KEY_FILE = os.path.expanduser("~/.config/openbanners-signing/play-service-account.json")

# edits.commit: when changesInReviewBehavior is not set, Play uses CANCEL_IN_REVIEW_AND_SUBMIT, which silently
# cancels whatever is in review (for example a production release) and resubmits everything. Never by accident.
ERROR_IF_IN_REVIEW = "ERROR_IF_IN_REVIEW"
CANCEL_IN_REVIEW_AND_SUBMIT = "CANCEL_IN_REVIEW_AND_SUBMIT"

IN_REVIEW = "RELEASE_LIFECYCLE_STATE_IN_REVIEW"
ALL_TRACKS = ("production", "beta", "alpha", "internal")


class PlayError(Exception):
    def __init__(self, status, body, what):
        self.status = status
        self.body = body
        self.reason = None
        self.message = body
        try:
            err = json.loads(body).get("error", {})
            self.message = err.get("message", body)
            for d in err.get("details", []) or []:
                if isinstance(d, dict) and d.get("reason"):
                    self.reason = d["reason"]
        except (ValueError, AttributeError):
            pass
        super().__init__(f"{what}: HTTP {status}: {self.message}" + (f" ({self.reason})" if self.reason else ""))

    @property
    def changes_in_review(self):
        return self.reason == "CHANGES_ALREADY_IN_REVIEW" or "already have changes in review" in str(self.message)


def load_service_account():
    raw = os.environ.get("PLAY_SERVICE_ACCOUNT_JSON", "").strip()
    if not raw:
        path = os.environ.get("PLAY_SERVICE_ACCOUNT_FILE") or DEFAULT_KEY_FILE
        if not os.path.exists(path):
            raise SystemExit("No Play credentials: set PLAY_SERVICE_ACCOUNT_JSON or PLAY_SERVICE_ACCOUNT_FILE.")
        with open(path) as f:
            raw = f.read()
    return json.loads(raw)


def _b64url(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def _sign_rs256(private_key_pem, data):
    with tempfile.TemporaryDirectory() as tmp:
        key_path = os.path.join(tmp, "key.pem")
        fd = os.open(key_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w") as f:
            f.write(private_key_pem)
        res = subprocess.run(["openssl", "dgst", "-sha256", "-sign", key_path], input=data,
                             capture_output=True, check=False)
        if res.returncode != 0:
            raise SystemExit("openssl could not sign the service-account JWT")
        return res.stdout


def access_token(sa):
    now = int(time.time())
    token_uri = sa.get("token_uri", "https://oauth2.googleapis.com/token")
    header = _b64url(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
    claims = _b64url(json.dumps({"iss": sa["client_email"], "scope": SCOPE, "aud": token_uri,
                                 "iat": now, "exp": now + 3600}).encode())
    unsigned = f"{header}.{claims}".encode()
    jwt = unsigned.decode() + "." + _b64url(_sign_rs256(sa["private_key"], unsigned))
    body = urllib.parse.urlencode({"grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
                                   "assertion": jwt}).encode()
    req = urllib.request.Request(token_uri, data=body, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return json.loads(resp.read())["access_token"]
    except urllib.error.HTTPError as e:
        raise PlayError(e.code, e.read().decode(errors="replace"), "OAuth token") from None


class Play:
    def __init__(self, package=PACKAGE, sa=None):
        self.package = package
        self.token = access_token(sa or load_service_account())

    def _call(self, method, url, what, json_body=None, data=None, content_type=None, params=None, timeout=120):
        if params:
            url += "?" + urllib.parse.urlencode(params)
        headers = {"Authorization": f"Bearer {self.token}"}
        if json_body is not None:
            data = json.dumps(json_body).encode()
            content_type = "application/json"
        if content_type:
            headers["Content-Type"] = content_type
        req = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                raw = resp.read()
                return json.loads(raw) if raw.strip() else {}
        except urllib.error.HTTPError as e:
            raise PlayError(e.code, e.read().decode(errors="replace"), what) from None

    def _app(self):
        return f"{API}/{self.package}"

    # --- edits ---------------------------------------------------------------------------------------------
    def edit_insert(self):
        return self._call("POST", f"{self._app()}/edits", "edits.insert", json_body={})["id"]

    def edit_delete(self, edit_id):
        try:
            self._call("DELETE", f"{self._app()}/edits/{edit_id}", "edits.delete")
        except PlayError as e:
            if e.status not in (400, 404):  # already committed/invalidated is fine
                raise

    def edit_commit(self, edit_id, changes_in_review_behavior=ERROR_IF_IN_REVIEW):
        return self._call("POST", f"{self._app()}/edits/{edit_id}:commit", "edits.commit",
                          params={"changesInReviewBehavior": changes_in_review_behavior})

    def track_get(self, edit_id, track):
        try:
            return self._call("GET", f"{self._app()}/edits/{edit_id}/tracks/{track}", f"edits.tracks.get {track}")
        except PlayError as e:
            if e.status == 404:
                return {"track": track, "releases": []}
            raise

    def track_update(self, edit_id, track, releases):
        return self._call("PUT", f"{self._app()}/edits/{edit_id}/tracks/{track}", f"edits.tracks.update {track}",
                          json_body={"track": track, "releases": releases})

    def bundles_list(self, edit_id):
        res = self._call("GET", f"{self._app()}/edits/{edit_id}/bundles", "edits.bundles.list")
        return sorted(int(b["versionCode"]) for b in res.get("bundles", []) or [])

    def bundle_upload(self, edit_id, path):
        with open(path, "rb") as f:
            data = f.read()
        res = self._call("POST", f"{UPLOAD_API}/{self.package}/edits/{edit_id}/bundles", "edits.bundles.upload",
                         data=data, content_type="application/octet-stream", params={"uploadType": "media"},
                         timeout=900)
        return int(res["versionCode"])

    # --- release summaries (review state; outside edits) ----------------------------------------------------
    def release_summaries(self, track):
        """applications.tracks.releases.list; [] if the track has no (non-obsolete) releases."""
        try:
            res = self._call("GET", f"{self._app()}/tracks/{track}/releases", f"tracks.releases.list {track}")
        except PlayError as e:
            if e.status == 404:
                return []
            raise
        return res.get("releases", []) or []

    def all_release_summaries(self, tracks=ALL_TRACKS):
        return {t: self.release_summaries(t) for t in tracks}


def track_version_codes(track_json, statuses=("completed", "inProgress", "halted")):
    codes = []
    for rel in track_json.get("releases", []) or []:
        if rel.get("status") in statuses:
            codes += [int(v) for v in rel.get("versionCodes", []) or []]
    return codes


def summary_version_codes(summaries, states):
    codes = []
    for s in summaries:
        if s.get("releaseLifecycleState") in states:
            codes += [int(a["versionCode"]) for a in s.get("activeArtifacts", []) or [] if "versionCode" in a]
    return codes


def in_review(summaries_by_track):
    """[(track, releaseName, [versionCodes])] for every release that is currently in review."""
    out = []
    for track, summaries in summaries_by_track.items():
        for s in summaries:
            if s.get("releaseLifecycleState") == IN_REVIEW:
                out.append((track, s.get("releaseName", ""),
                            [int(a["versionCode"]) for a in s.get("activeArtifacts", []) or [] if "versionCode" in a]))
    return out


def describe_summaries(summaries_by_track):
    lines = []
    for track, summaries in summaries_by_track.items():
        if not summaries:
            lines.append(f"{track}: (no releases)")
        for s in summaries:
            codes = ",".join(str(a.get("versionCode")) for a in s.get("activeArtifacts", []) or [])
            state = s.get("releaseLifecycleState", "?").replace("RELEASE_LIFECYCLE_STATE_", "")
            lines.append(f"{track}: {s.get('releaseName', '')} [{codes}] {state}")
    return lines


def write_summary(markdown):
    """Append to the GitHub Actions job summary (no-op outside Actions)."""
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a") as f:
            f.write(markdown.rstrip() + "\n\n")
