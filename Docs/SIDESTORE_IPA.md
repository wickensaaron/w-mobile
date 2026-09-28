# W Media Player IPA for SideStore

The iOS app is built from the Kotlin Multiplatform source on macOS. The repository's `codemagic.yaml` runs its existing iOS dependency preparation and unsigned device-IPA packaging scripts on a Codemagic Mac. SideStore then signs the IPA with the device owner's Apple Account when it is installed.

The owner chose Codemagic and authorised the source push and cloud IPA build on 28 September 2026. Three Mac build attempts have run; none produced an IPA. Use the current authorised build scope for retries, and obtain a new instruction before publishing elsewhere or enabling paid machines/billing.

## Current build evidence — 28 September 2026

| Codemagic build | Source commit | Result |
|---|---|---|
| `6abaca9df7280e0c166c256c` | `930755a659aa29de076a2ca3290f125508f72a54` | Failed. Checkout exposed missing declarations for three existing gitlinks; dependency preparation proceeded, then native Kotlin compilation found JVM-only badge-loader locking. |
| `6abacc8d679033ef824d48ee` | `730acb461fd1def04b4986f93bfe27f9b0107031` | Checkout and Kotlin compilation completed. `linkReleaseFrameworkIosArm64` failed with Java heap space after 9m29s. The daemon reported an effective maximum heap of 2.5 GiB, despite the workflow requesting 4608 MiB. |
| `6abad1ab22e12a9e066ef6de` | `21fff02234ebd931e58ccc874f9fa4dc9f552bd7` | Effective Gradle heap verified at 4608 MiB on a 10 GiB Mac. Native Release linking still failed with Java heap space in `DevirtualizationAnalysis` after 5m31s. Swift app compilation was not reached. |

The missing gitlink declarations and portable badge locking are fixed in `730acb46`. Commit `21fff022` forwards explicit Gradle memory settings and verifies effective heap. The next bounded retry increases it to 7168 MiB, retains one Gradle worker and limits Xcode to one job. The script requires the requested heap plus 2 GiB of physical memory. This capacity check does not prove working-set safety: metaspace/native/system memory also consume the measured 10 GiB. No further heap increase is planned if this bound fails. Release optimisation, Kotlin 2.4.10 and the free M2 machine remain unchanged. Shell/argument-boundary checks passed without Gradle/Xcode; the cloud build must establish linking and Swift success.

Mobile Live TV sources are session-only and do not yet import encrypted Windows/TV sources or resolve their archive identities. A prepared boundary hides unsupported imported history cards and blocks generic addon playback/metadata while retaining saved progress. Its focused assertions are unrun; shared live-channel logos, replay and provider persistence remain required features.

## Codemagic setup

The repository and public configuration group have been used by the authorised attempts above. For a fresh account or project, perform these cloud steps within the owner's explicit push/build authorisation.

1. Connect the `wickensaaron/w-mobile` repository in a personal Codemagic account and select the branch containing `codemagic.yaml`.
2. In that application's **Environment variables** tab, add `NUVIO_SUPABASE_ANON_KEY` with the project's **publishable** Supabase key and assign it to a variable group named **`w_media_public`**. Create that application group if it does not exist. The workflow imports this exact group; adding the variable without the matching imported group does not make it available to the build. The project URL is already in the YAML. Do not enter a Supabase secret key, Google OAuth client secret, or database password.
3. Run the `W Media Player SideStore IPA` workflow manually. It does not publish to the App Store or TestFlight.
4. Download `W-Media-Player-*-full-release.ipa` from the build artifacts. Keep the IPA private if it contains private app configuration.
5. Open the IPA with SideStore on the iPhone. Follow SideStore's Apple Account, Developer Mode, and refresh instructions. SideStore's normal free-account signing needs refresh about every seven days.

The Codemagic workflow requires the public Supabase configuration variable so it cannot produce an IPA with an unconfigured Google sign-in button. Its preflight rejects `sb_secret_` keys and legacy JWTs whose role is not `anon`, without printing their values. Publishable keys and legacy `anon` keys are public client inputs; this format check does not verify their signature or prove they belong to this project. A present key still needs an actual sign-in check. The cloud checkout must not contain `local.properties`, whose runtime values override environment variables; that file is ignored by Git and is not uploaded with this workflow. Direct Mac builds and the existing GitHub workflow retain their separate local-properties configuration and must use public client keys as well. It downloads the pinned Nuvio Engine Apple dependency and initializes MPVKit at the commit recorded in the source checkout. Dependency preparation validates both engine slices and their headers: Gradle configures the simulator target even for a device IPA. The build checks the bundle ID, W Media Player display name, arm64 architecture, widget, and unsigned IPA contents before publishing the artifact. Alongside the IPA, download its `.sha256` and `-build-info.txt` files to retain the checksum, exact source/MPVKit commits, Apple toolchain and device architecture evidence. These files exclude runtime keys and account data.

The build runs on the Codemagic Mac; no native build runs on this Windows PC. A successful Mac build and a SideStore install on an iPhone are still required before calling the IPA verified. The workflow selects Java17 and retains the Xcode build log, including failures. The second attempt's log used the Xcode26.6 installation; YAML remains `latest`, and no successful complete build has yet established a tested toolchain pin. Dependency preparation restored the pinned player components on the Mac. `NUVIO_ENGINE_ROOT`, if set, is shared by preparation and Gradle. Shell scripts are checked out with LF line endings.

The retained Supabase redirect configuration contains `wmedia://auth/google` and `wmedia://auth/confirm`. The native app declares the `wmedia` URL scheme and forwards `onOpenURL` callbacks to the shared handler. These are configuration/wiring checks; real iPhone Google sign-in, account ownership and sync remain unverified.

Local phone layout preparation adds keyboard/safe-area handling to sign-in, inset-aware Live TV spacing, wrapping narrow-screen controls and a sticky guide time row. Actual iPhone keyboard, notch, rotation, larger text, sign-in, playback and sync still require device checks.

References: [Codemagic Kotlin Multiplatform builds](https://docs.codemagic.io/yaml-quick-start/building-a-kmm-app/), [Codemagic variable groups](https://docs.codemagic.io/yaml-basic-configuration/configuring-environment-variables/), [Codemagic personal pricing](https://codemagic.io/pricing/), [SideStore installation](https://docs.sidestore.io/docs/installation/install).
