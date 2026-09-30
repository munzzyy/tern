# Changelog

## Unreleased

Everything Obtainium does, and more.

- Reads the stores and sites Obtainium reads: APKPure, Aptoide, Uptodown,
  APKCombo, APKMirror (tracking only), Farsroid, Huawei AppGallery, Samsung
  Galaxy Store, vivo, Tencent, CoolApk, RuStore, itch.io, Telegram,
  NeutronCode, LiteAPKs, Apk4Free and RockMods (tracking only). The Add screen
  says when a store offers again what developers publish elsewhere, and when a
  site offers apps changed by someone else. Every file is still held to the
  certificate of the first install.
- Fetches each file from where its source says it is at the moment of the
  download, for stores whose addresses expire.
- Searches F-Droid, Aptoide, Uptodown, AppGallery, the vivo store and RuStore
  as well as GitHub, Codeberg and GitLab, in the places ticked under "Search in".
- Installs through Shizuku, root or another installer app. Google Play can be
  named as the installer, for every app or for one, and with Let Me Downgrade
  installed an older version can go in over a newer one.
- Every per-app option Obtainium has: pre-releases, falling back to older
  releases, a minimum age, filters on titles, notes and versions, the version
  read from the tag, the title or the date with a match group, the order of
  releases, staying some releases behind the newest, zip and tar archives with a filter
  inside them, a name and author of your own, muted notifications, refreshing
  before a download, and the options of each source, from the steps through a
  web page to the device model the Galaxy Store is asked as. Both import and
  export carry all of them.
- Checks as often as every 15 minutes or as seldom as every 30 days, on start
  and on opening an app if wanted, only installed apps if wanted, with a filter
  for every app's files, and forgets apps removed from the phone if wanted.
- The list can be sorted, grouped by category or source with groups that fold,
  filtered, and swiped. Favourites stay on top, each app's categories show as
  a coloured stripe, and a double tap on an icon opens the app. A minimal
  density, haptics and an always-phone layout are settings.
- Categories have colours, and can be renamed or deleted for every app at once.
- Notifications carry Update and Update all buttons. Releases of tracked apps
  have their own channel, and a quiet notification can show a check running.
- Imports a list of addresses from any text, and `obtainium://apps` links of
  several apps. `tern://refresh` and `obtainium://refresh` check from a link.
- Keeps an export up to date in a folder you pick, can carry settings in it,
  and writes a file Obtainium can import.
- A home-screen widget with the number of updates and Check and Update all
  buttons, a Quick Settings tile, and launcher shortcuts.
- Saves any file a release offers to Downloads, and the source code of GitHub,
  GitLab and Forgejo releases, from an app's page, one of its versions or many
  apps at once. Saving goes on after the page is left and says when each file
  is saved; sizes are asked of the server where a source lists none. Many
  tracked releases can be marked as seen at once.
- The notification after an install names the version, and one about problems
  names each app with its reason, a tap away from what went wrong.
- New apps can be shown to Verified Apps first, as in Obtainium. The installer
  app is picked from a list with icons, with the way in it takes.
- Notes of your own and category chips on an app's page, and the project's
  README, read from the forge when you ask for it.
- The activity log can be shared as text, and Tern offers to keep itself up to
  date.
- The top of an app's page takes the colour of its icon, and a deeper screen
  slides in from the side text runs to.
- The language can be chosen in Tern on every Android version.
- How often to check is a slider from 15 minutes to 30 days. Install only on
  Wi-Fi and only while charging hold back installs, not checks, and a switch
  pauses every install the background would make. A check that failed on the
  network is tried again, later each time, and a rate limit is waited out.
- Your own colour can be typed as a colour code, and the scheme can be
  standard, vibrant or expressive, each measured for contrast. A category's
  colour can be any colour.
- The kept export can be written in Obtainium's format under a name of your
  own, and the export for Obtainium works on a TV too.
- Certificate pinning for GitHub, GitLab and Codeberg, off by default as in
  Obtainium. RuStore's addresses that use Russia's national authority work.
- After an unexpected stop the next start shows what Tern was doing, to copy
  or share. The log can be cut to its last days and is copied where nothing
  takes a share.
- About links to how Tern works, how it keeps you safe and its privacy page,
  with a note on Android's developer verification.
- The list's filters combine: name, developer, package, sources, several
  categories, up to date, not installed, tracked only. Each row shows when its
  release came out with its changes a tap away, dims the icon of an app not
  installed, marks a tracked release as seen, and says when a project moved.
- Many apps can be installed, filed under categories or shared at once. Update
  all can take first installs too, or be hidden, and can ask first. A check of
  the whole list shows how far it got.
- An app's settings can be shared as a link that Obtainium opens too, and Tern
  reads such links back; they never carry a token.
- Read as: the Add screen can force how an address is read, with the source's
  options and a package name set before adding. Search names what failed,
  searches any Forgejo, takes a fewest-stars limit, filters its results and
  searches a repository by words. Imports offer to replace apps already there
  and never change them unasked.
- Releases are picked as Obtainium picks them: web pages in its link order with
  up to ten steps, the version from a link or the whole page, last matches,
  tags for tracked projects without releases, a minimum age that can follow the
  setting for all apps. Private GitHub projects download with their token, and
  GitHub can be reached through a hubproxy.
- Tar archives compressed with bzip2 or xz are opened. Downloads can be
  cancelled from the list and the notification, are tried again on a network
  failure, and can go one at a time. The file picked to install is kept for
  later updates. Tern updates itself last.
- RuStore apps that come as a base and splits install as one, every part held
  to the base's signer. The OBB files of an XAPK are put in `Android/obb` with
  Shizuku or root, under the package the checks verified.
- F-Droid apps show their author and changelog, APKMirror apps what changed
  and the size of the file, and a SourceForge project can be followed in one
  folder. The package name can be set on an app's page as well as when adding.
  An app without one leaves in Obtainium's format with an id Obtainium replaces
  at the first install, and comes back without one.

## 0.1.0, 2026-09-29

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
