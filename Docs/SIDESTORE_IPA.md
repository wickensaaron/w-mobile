# W Media Player IPA for SideStore

The iOS app is built from the Kotlin Multiplatform source on macOS. The repository's `codemagic.yaml` runs its existing iOS dependency preparation and unsigned device-IPA packaging scripts on a Codemagic Mac. SideStore then signs the IPA with the device owner's Apple Account when it is installed.

## Codemagic setup

1. Connect the `wickensaaron/w-mobile` repository in a personal Codemagic account and select the branch containing `codemagic.yaml`.
2. In that application's environment variables, add `NUVIO_SUPABASE_ANON_KEY` with the project's **publishable** Supabase key. The project URL is already in the YAML. Do not enter a Supabase secret key, Google OAuth client secret, or database password.
3. Run the `W Media Player SideStore IPA` workflow manually. It does not publish to the App Store or TestFlight.
4. Download `W-Media-Player-*-full-release.ipa` from the build artifacts. Keep the IPA private if it contains private app configuration.
5. Open the IPA with SideStore on the iPhone. Follow SideStore's Apple Account, Developer Mode, and refresh instructions. SideStore's normal free-account signing needs refresh about every seven days.

The workflow requires the public Supabase configuration variable so it cannot produce an IPA with a broken Google sign-in button. It downloads the pinned Nuvio Engine Apple dependency and initializes MPVKit using the existing repository scripts. The build checks the bundle ID, W Media Player display name, arm64 architecture, widget, and unsigned IPA contents before publishing the artifact.

The iOS build has **not yet run** in this Windows workspace. A successful Codemagic build and a SideStore install on an iPhone are still required before calling the IPA verified.

References: [Codemagic Kotlin Multiplatform builds](https://docs.codemagic.io/yaml-quick-start/building-a-kmm-app/), [Codemagic personal pricing](https://codemagic.io/pricing/), [SideStore installation](https://docs.sidestore.io/docs/installation/install).
