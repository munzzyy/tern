# Changelog

## 0.1.0, not released yet

The first version.

- Follows releases on GitHub, GitHub Actions, GitLab, Forgejo and Codeberg,
  F-Droid, IzzyOnDroid, any F-Droid format repository, web pages, direct links,
  Jenkins, SourceHut and SourceForge.
- Reads a file's package, version code and signing certificate from the server
  before downloading it.
- Checks every download: publisher's checksum, two parsers, signer against the
  installed app and the pin, package, version, kind.
- Verifies the signature of every file itself, next to Android: schemes v1, v2,
  v3 and v3.1, the digest of the whole file, and every link of a rotated key.
  A part of a bundle that Android does not read is no longer taken at its word.
- Updates without a prompt where Android allows it, and says so where it does not.
- Background checks through Android's job scheduler.
- Imports Obtainium's export file, and adds apps from a GitHub user's stars.
- Several apps can be selected at once to file under a category, check,
  update or remove.
- A project that moved can be followed to its new address when the package and
  the signer are the same there.
- Phone, tablet and keyboard or D-pad layouts, light, dark and black themes.
- Android TV: every screen works with the remote alone.
- Asks for Android's permission to install apps before the first install, and
  carries on with the install afterwards.
- English and 28 machine translations, right-to-left languages included.
- Send from a phone: a TV shows a QR code and a code of 20 characters, the
  phone's browser seals the links or the export file with it, and the TV opens
  only what was sealed with that code. Nothing is added until you look at it.
- Tor through Orbot. Every request goes through the proxy or fails and says so.
  Settings shows whether Orbot is connected and opens it when it is not.
- Carries the developer's certificate of 15 well-known apps, so even their first
  install is checked, and offers well-known apps to start from.
- A look of your own: the theme, colours from the wallpaper, a palette or a
  colour you pick, contrast, density, corners and icon shapes, with a preview.
- The app list, the page of an app, the activity log and the first run were
  drawn again, with every check explained where it is shown.
- Import and export on a device without a file picker, as most TVs are.
- A switch keeps the names of apps out of notifications and off the lock screen.
- A second check with AppVerifier, where AppVerifier is installed.
- A redirect from the internet into the local network is refused, and Android 16
  is asked for certificate transparency.
- The build is reproducible, and everything it downloads is held to a hash.
- No in Android's installer is a cancel: the row goes back to what it was, and
  the file stays for the next try.
- A "Get it with Tern" badge for a README opens the app in Tern with one tap.
