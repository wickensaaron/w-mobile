# W Media Player IPA for SideStore

The iOS app is built from the Kotlin Multiplatform source on macOS. The repository's `codemagic.yaml` runs its existing iOS dependency preparation and unsigned device-IPA packaging scripts on a Codemagic Mac. SideStore then signs the IPA with the device owner's Apple Account when it is installed.

The owner chose Codemagic, with an explicit gate: keep preparation local and do not push the source or start a cloud build until the owner says to do so. The workflow is prepared in the local repository but has not produced an IPA.

## Codemagic setup

Perform these cloud steps only after the owner's explicit instruction to push and build.

1. Connect the `wickensaaron/w-mobile` repository in a personal Codemagic account and select the branch containing `codemagic.yaml`.
2. In that application's **Environment variables** tab, add `NUVIO_SUPABASE_ANON_KEY` with the project's **publishable** Supabase key and assign it to a variable group named **`w_media_public`**. Create that application group if it does not exist. The workflow imports this exact group; adding the variable without the matching imported group does not make it available to the build. The project URL is already in the YAML. Do not enter a Supabase secret key, Google OAuth client secret, or database password.
3. Run the `W Media Player SideStore IPA` workflow manually. It does not publish to the App Store or TestFlight.
4. Download `W-Media-Player-*-full-release.ipa` from the build artifacts. Keep the IPA private if it contains private app configuration.
5. Open the IPA with SideStore on the iPhone. Follow SideStore's Apple Account, Developer Mode, and refresh instructions. SideStore's normal free-account signing needs refresh about every seven days.

The Codemagic workflow requires the public Supabase configuration variable so it cannot produce an IPA with an unconfigured Google sign-in button. Its preflight rejects `sb_secret_` keys and legacy JWTs whose role is not `anon`, without printing their values. Publishable keys and legacy `anon` keys are public client inputs; this format check does not verify their signature or prove they belong to this project. A present key still needs an actual sign-in check. The cloud checkout must not contain `local.properties`, whose runtime values override environment variables; that file is ignored by Git and is not uploaded with this workflow. Direct Mac builds and the existing GitHub workflow retain their separate local-properties configuration and must use public client keys as well. It downloads the pinned Nuvio Engine Apple dependency and initializes MPVKit at the commit recorded in the source checkout. Dependency preparation validates both engine slices and their headers: Gradle configures the simulator target even for a device IPA. The build checks the bundle ID, W Media Player display name, arm64 architecture, widget, and unsigned IPA contents before publishing the artifact. Alongside the IPA, download its `.sha256` and `-build-info.txt` files to retain the checksum, exact source/MPVKit commits, Apple toolchain and device architecture evidence. These files exclude runtime keys and account data.

The iOS build has **not yet run** in this Windows workspace. A successful Codemagic build and a SideStore install on an iPhone are still required before calling the IPA verified. The workflow selects Java17 and retains the Xcode build log, including failures. Xcode currently remains `latest`; select and record a tested version during the first authorised build rather than treating Windows checks as iOS compatibility proof. MPVKit and the Apple engine framework are absent locally; dependency preparation restores the pinned versions on the Mac. `NUVIO_ENGINE_ROOT`, if set, is now shared by preparation and Gradle. Shell scripts are checked out with LF line endings.

Local phone layout preparation adds keyboard/safe-area handling to sign-in, inset-aware Live TV spacing, wrapping narrow-screen controls and a sticky guide time row. Actual iPhone keyboard, notch, rotation, larger text, sign-in, playback and sync still require device checks.

References: [Codemagic Kotlin Multiplatform builds](https://docs.codemagic.io/yaml-quick-start/building-a-kmm-app/), [Codemagic variable groups](https://docs.codemagic.io/yaml-basic-configuration/configuring-environment-variables/), [Codemagic personal pricing](https://codemagic.io/pricing/), [SideStore installation](https://docs.sidestore.io/docs/installation/install).
