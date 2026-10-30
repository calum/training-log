# Setting up the public data repo

You do this once. Take it slowly; nothing here can break your phone data.

## 1. Create the repository

1. On github.com click **+** (top right), then **New repository**.
2. Name it, for example, `training-data`. Choose **Public**. Tick **Add a README file**. Click **Create repository**.

## 2. Copy these files in

Copy everything from this template folder into the new repo, keeping the folder names:

- `README.md` (replace the one GitHub made)
- `scripts/make_chart.py`
- `.github/workflows/chart.yml`

Easiest way: on the repo page choose **Add file**, **Upload files**, drag the folders in, then **Commit changes**. If the hidden `.github` folder does not show in your file picker, use **Add file**, **Create new file**, type `.github/workflows/chart.yml` as the name and paste the contents.

## 3. Check Actions can write

Repo **Settings**, **Actions**, **General**, **Workflow permissions**: pick **Read and write permissions** and save. (The workflow also asks for this itself.)

## 4. Sign in from the phone

**Recommended: device login.** Make your own small GitHub App and sign in with a code; it renews itself so you never paste a token again. Follow `docs/github-app-setup.md` in the app repo, then use **Sign in with GitHub** in the app. If you do that, skip the token steps below.

**Fallback: personal access token.** In the app choose **Use a personal access token instead**, then create a fine-grained token:

1. GitHub profile picture, **Settings**, **Developer settings**, **Personal access tokens**, **Fine-grained tokens**, **Generate new token**.
2. Name: `training-app`. **Expiration**: pick a limit such as 90 days or 1 year (not "no expiration").
3. **Repository access**: choose **Only select repositories** and pick ONLY your data repo.
4. **Repository permissions**: set **Contents** to **Read and write**. Leave everything else as it is.
5. Click **Generate token** and copy it now; GitHub shows it only once.
6. Paste it into the app's sync settings on your phone.

## 5. Revoking a token or sign-in

Device login: on github.com open **Settings**, **Applications**, **Authorized GitHub Apps** and revoke your app (or uninstall it). Personal access token:

Go to **Settings**, **Developer settings**, **Personal access tokens**, **Fine-grained tokens**, open `training-app`, and click **Delete** (or **Revoke**). The app stops syncing immediately; make a new token whenever you want to resume. Do this at once if you think the token leaked or you lose the phone.

## Never commit tokens

Never paste a token into a file, issue, commit or chat, and never commit it to any repo. The repo is public: anyone could read it. If it happens, revoke the token first, then make a new one.

## What gets uploaded

Only gym sets, treadmill sessions and weekly summaries. See `README.md` for the exact list of what is and is not included.
