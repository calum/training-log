# Sign in to GitHub from the app (GitHub App, device login)

Instead of pasting a personal access token that expires and has to be pasted again, the app can sign in the way a TV app does: it shows a short code, you type it on github.com once, and it keeps itself signed in. You make a tiny GitHub App that belongs only to you. It takes about five minutes and you do it once.

## What you get, and why it is safer

- The app only ever receives a **user token for your own GitHub App**. It can touch only the repository you install the App on, and only with the one permission you give it (Contents: Read and write).
- The token **expires after 8 hours**. The app renews it by itself with a refresh token (valid for about 6 months, renewed each time it is used), so background sync keeps working without you. If renewing ever fails, the app shows "Sign in again".
- Nothing secret is built into the app or this public repo. The **Client ID** is public by design. You never need, and must never enter, a *client secret*.
- Tokens are stored on your phone encrypted with the Android Keystore, and are never shown after signing in.

## 1. Create the GitHub App

1. On github.com open your profile picture, **Settings**, then at the bottom left **Developer settings**, **GitHub Apps**, **New GitHub App**.
2. **GitHub App name**: anything unique, for example `calum-training-log-sync`.
3. **Homepage URL**: any address, for example your repo page `https://github.com/calum/training-log`.
4. **Identifying and authorizing users**: tick **Enable Device Flow**. Leave **Expire user authorization tokens** ticked (it is on by default, and the app depends on it). You can ignore the callback URL.
5. **Post installation**: leave everything blank.
6. **Webhook**: untick **Active**. Turn webhooks OFF; the app does not need them.
7. **Permissions**, **Repository permissions**: set **Contents** to **Read and write**. Leave every other permission at "No access". (GitHub adds **Metadata: Read-only** automatically; that is fine.)
8. **Where can this GitHub App be installed?** Choose **Only on this account**.
9. Click **Create GitHub App**.
10. On the next page, copy the **Client ID** (it looks like `Iv23li...`). This is the only value the phone needs. Do **not** generate a client secret or a private key.

## 2. Install it on the one repository

1. On the App's page choose **Install App** in the left menu, then **Install** next to your account.
2. Choose **Only select repositories** and pick the single repository `calum/training-log` (or whatever your data repo is). Click **Install**.

## 3. Sign in on the phone

1. In the app open **Backup & sync**. Check the username (`calum`), repo (`training-log`) and branch (`data`).
2. Paste the **Client ID** into the "GitHub App Client ID" box.
3. Tap **Sign in with GitHub**. A code like `ABCD-1234` appears. Tap **Copy code**, then **Open github.com/login/device**, paste the code, and approve.
4. Return to the app. It finishes by itself and starts syncing.

Prefer the old way? Tap **Use a personal access token instead** and follow `docs/data-repo-template/SETUP.md`. Both methods work.

## Keeping `main` safe

The sync only writes to the **`data`** branch (the sync target). Even so, protect `main` so that a leaked token could never change code:

1. Repo **Settings**, **Rules**, **Rulesets**, **New ruleset**, **New branch ruleset**.
2. Name it `protect main`, set Enforcement to **Active**, and under Target branches add **Include default branch** (or `main`).
3. Tick **Restrict deletions**, **Require a pull request before merging** and **Block force pushes**. Add yourself under **Bypass list** only if you want to keep pushing directly.

Because the App can only reach this one repository, and `main` rejects direct pushes, a stolen token could at worst alter the public numbers on the `data` branch.

## If something goes wrong

- **"Device flow is off"**: re-open the App's settings and tick **Enable Device Flow**.
- **"GitHub doesn't know that Client ID"**: copy it again from the App's page (not the App ID).
- **Repo not found** after signing in: the App is not installed on that repository. Redo step 2.
- **Asked to sign in again**: tap **Sign in with GitHub**. Your sessions stay queued on the phone and upload afterwards.
- **To revoke**: on github.com, **Settings**, **Applications**, **Authorized GitHub Apps**, then **Revoke** next to the App (or uninstall it). In the app tap **Disconnect**.

## Restoring numbers on a new phone

Install the app, sign in as above, then use **Restore numbers from GitHub** in Backup & sync. It brings back weights, reps and treadmill sessions. Feelings and knee scores are private and are restored from the Drive backup instead. Existing sessions are never overwritten.
