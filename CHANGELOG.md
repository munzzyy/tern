# Changelog

## 0.2.2, 2026-10-01

- The well known apps show the address each one comes from under its name,
  and the list says that opening one asks that address for its newest
  release. Nothing is asked before you open one.

## 0.2.1, 2026-09-30

- Runs on Android 9. Saving into Download/Tern without asking needs Android
  10, so on 9 the kept export and a file saved from an app's page go through
  the system's file picker, and the settings say where a picker is needed.
- On Android 9 the system's install confirmation now shows. It arrived in a
  form Tern did not expect and was never put on screen.
- A file that needs a newer Android than the phone has is named for that,
  instead of being called unreadable.
- On Android 9, Tern says once that Google's last security fixes for it came
  out in January 2022, and that checking files cannot close holes in Android.
- Persian text added in 0.2.0 has its zero-width non-joiners back.

## 0.2.0, 2026-09-30

Nearly everything Obtainium does, done in a way Tern can stand behind, and
more.

Sources
- Reads itch.io, Telegram and Neutron Code. Behind a setting that starts off,
  it also reads the third-party stores APKPure, Aptoide, APKCombo, APKMirror
  (tracking only), Tencent, Huawei AppGallery, Samsung Galaxy Store and vivo.
  While the setting is off, a pasted store address says what the stores are
  and offers to turn them on, a search leaves them out, and apps from them
  wait in the list, paused.
- Every source is asked as Tern, with its own User-Agent, and nothing is
  borrowed from another app. Tern does not read Uptodown, RuStore or CoolApk,
  which answer only their own apps, or LiteAPKs, Apk4Free, RockMods and
  Farsroid, which offer modified apps. An Obtainium import keeps everything
  else and names each app it left out, with the reason.
- The Add screen, an app's page and an import say where a store's files come
  from and what the first install decides. A store's checksum is called the
  store's, not the developer's.
- The certificates Tern carries for well-known apps are found by package too,
  so such an app added from a store, a mirror or a web page is held to its
  developer's certificate from the first install.
- Fetches each file from where its source says it is at the moment of the
  download, for sources whose addresses expire.
- Searches F-Droid as well as GitHub, Codeberg and GitLab, and Aptoide,
  AppGallery and the vivo store once the stores are on, in the places ticked
  under "Search in".

Installing
- Installs through Shizuku, Dhizuku, root or another installer app, and the
  log says when Android's installer stood in for one that was not ready.
  Shizuku and Dhizuku installed and updated apps without a prompt on an
  Android 13 emulator; root has not been tried on a device yet.
  Every install, whichever installer made it, counts only when the installed
  app carries the certificate the checks verified. Shizuku's library asks Sui
  for anything only once Shizuku is the chosen installer.
- Dhizuku's state shows in Settings: not installed, not the device owner, not
  allowed yet, or ready. The installer app is picked from a list with icons.
- Google Play can be named as the installer, for every app or for one. With
  Let Me Downgrade installed, an older version can go in over a newer one,
  only for a release picked from an app's history, and never when the file is
  older than the version its source named.
- Zip and tar archives, with gzip, bzip2 and xz, and a filter for the file
  inside them.
- New apps can be shown to Verified Apps or AppVerifier first, only when that
  app carries the certificate its makers publish. Off until turned on.

Options and checks
- Every per-app option Obtainium has: pre-releases, falling back to older
  releases, a minimum age, filters on titles, notes and versions, the version
  read from the tag, the title or the date with a match group, the order of
  releases, staying some releases behind the newest, a name and author of your
  own, muted notifications, refreshing before a download, and the options of
  each source, from the steps through a web page to the phone model the Galaxy
  Store is asked for. Import and export carry all of them.
- How often to check is a slider from 15 minutes to 30 days. Checks can run on
  start and on opening an app, cover only installed apps, and filter every
  app's files.
- Checks and installs each have their own Wi-Fi and charging switches, and a
  copy that held checks back for Wi-Fi or charging on 0.1.0 still does. One
  switch pauses every install the background would make. An install held for
  Wi-Fi or a charger goes in from the last check once they are there. A check
  that failed on the network is tried again, later each time, and a rate limit
  is waited out.
- Web page apps saved by 0.1.0 offer what they offered before, and a switch
  moves one to Obtainium's way of reading a page.
- Apps removed from the phone can be forgotten if you want. Their pinned
  certificates are kept for when their source is added again, and apps Android
  15 archives stay in the list.

The list and the screens
- The list can be sorted, grouped by category or source with groups that fold,
  filtered, and swiped. Favourites stay on top, each app's categories show as a
  coloured stripe, and a double tap on an icon opens the app. A minimal
  density, haptics and an always-phone layout are settings. Categories have
  colours of their own and can be renamed or deleted for every app at once.
- With a remote, a grouped list opens on its first app and back returns to the
  row that was opened, rows of chips walk in order, and the buttons beside the
  interval slider say when a press turns checks off. Select all picks only the
  apps the list shows.
- From one and a half times the text size, a television shows the list and an
  app's page one at a time, and a screen's title that does not fit beside its
  buttons goes under them whole.
- A home-screen widget with the number of updates and Check and Update all
  buttons, a Quick Settings tile, and launcher shortcuts. Only Tern's own
  shortcuts can start Update all, Add or a check. A `tern://refresh` or
  `obtainium://refresh` link from a web page or another app asks first, at
  most once a minute, and never installs.
- The well known apps say when one is already in your list or on the phone,
  and when the copy on the phone is signed by someone else, so this address
  could not update it.
- Notifications carry Update and Update all buttons, name the version an app
  was updated to, and name each app with a problem and its reason.
- Notes of your own and category chips on an app's page, and the project's
  README, read from the forge when you ask for it, a private one included.
- The top of an app's page takes the colour of its icon, and a deeper screen
  slides in from the side text runs to. Your own colour can be typed as a
  colour code, and the scheme can be standard, vibrant or expressive, each
  measured for contrast.
- The language can be chosen in Tern on every Android version.

Import, export and sharing
- Imports a list of addresses from any text, and `obtainium://apps` links.
- Keeps an export up to date in a folder you pick, with settings if you want,
  in Tern's format or Obtainium's under a name of your own.
- Shared links keep the address or the app's settings after the # on
  tern.munzzyy.dev, where no server sees them. "Share as an Obtainium link"
  makes one Obtainium opens. Exports and shared files never carry a token or a
  request header that could hold a key.
- Saves any file a release offers to Downloads, and the source code of GitHub,
  GitLab and Forgejo releases, from an app's page, one of its versions or many
  apps at once.

Privacy and safety
- Certificate pinning for GitHub, GitLab and Codeberg, off by default. The
  hubproxy setting says plainly that a hubproxy can change what GitHub seems to
  publish, and a checksum that came through one names it.
- The activity log can keep Tern's own warnings, errors and checks, off at
  first. The shared log and the crash report shown after an unexpected stop
  lose query strings, tokens, cookies, session ids and passwords first.
- A copy of Tern that F-Droid installed follows its F-Droid entry for its own
  updates. Following GitHub instead says it skips F-Droid's checks.
- PRIVACY.md lists every host Tern can talk to and when, and the store listing
  sums it up.
- The network door check in CI also fails on the other ways an Android app can
  reach the network, in the source and in the built APK.

Every new string is in all 28 languages, machine-translated and checked.

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
