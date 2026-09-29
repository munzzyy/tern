# What Stamp protects against

This page says what Stamp checks, in what order, and where its checks end.
It is written so that someone can disagree with it point by point.

## Who is trusted

The developer of an app, as identified by the certificate their releases are
signed with. Stamp's job is to make sure that what gets installed comes from
that certificate and is the file the developer published.

Not trusted: the network, a mirror, a repository index that is not signed, a
link someone sent you, an import file, and any text a server sends.

## The first install

Nothing on the phone says who the developer is yet, so the first install is
trust on first use. Stamp shows the certificate the file claims before you
install, as a fingerprint you can compare with one the developer publishes.
After Android has installed the file, that certificate is pinned.

A pin can also arrive with an import or a link. Such a pin is a decision somebody
else made, and the Add screen says so before anything is stored.

## Every install after that

The checks run in this order, and the first one that fails ends it.

### The checksum

The file's SHA-256 is compared with the publisher's checksum when one exists. A
mismatch deletes the file.

A checksum published next to the file protects against damage and against a
tampering mirror or cache. It does not protect against someone who controls the
release page, because they control the checksum too. The signer check is what
covers that case.

### Two readers

Stamp's parser and Android's package parser both read the file. If they
disagree on the package name or version code, the file is refused. Every later
decision uses the certificates Android reported.

### The signer

The file must be signed by the signer of the installed app, and by the pinned
certificate if there is one. A rotated key is accepted where the file proves
descent from the known one. In a bundle, every part must carry the base's
signer.

### Package, version and kind

A file for another package is refused. So is an older version code, a test-only
build, and a build for a newer Android than the phone runs.

### Android

The system installer runs its own checks after all of that. Stamp treats an
install as done when Android reports success and the package manager shows the
expected version code.

## Reading a file before downloading it

Stamp reads a file's manifest and signing block from the server with range
requests, to show what an update is before fetching it and to decide by version
code. What it reads there is what the file claims. No signature is verified at
that point, and the screens say "claims" until Android has read the downloaded
file.

## Repositories in F-Droid's format

The repository's `entry.jar` must carry a valid signature by exactly one
certificate. The first contact pins it, or the address names it
(`?fingerprint=`). From then on a different signer is refused, an index older
than the last one seen is refused, the index and every diff must match the hash
in the signed entry, and file names that would leave the repository are
refused. Checksums and signers from a verified index are used for the download
and the signer check.

F-Droid and IzzyOnDroid added by an app's page go through their per-app
listings, which carry no signature. TLS protects that path, and the signer pin
protects the install.

## The network

HTTPS only, enforced in four places: the manifest, the network security
configuration, the request wrapper in `core`, and the Android client. Redirects
are followed by hand, and each hop must be HTTPS.

A token is stored for one exact host, encrypted under a key in the Android
Keystore that cannot be exported. It is sent to that host only. When a redirect
changes the host, the token is dropped for the rest of the chain. Tokens are not
part of exports and are never written to the log.

With a SOCKS proxy set, host names are resolved by the proxy. This was checked
with a logging proxy and a packet capture, not with Orbot itself.

## Links, shares and imports

A link or a shared text fills in the Add screen and nothing more. `obtainium://`
links are handled only after the user turns that on. An import file is checked
when read: known source types, web addresses, real package names, bounded
lengths. Filters and pins that came from outside are shown as such.

## Text from servers

Release notes are parsed into a small block model and drawn by the app. There is
no web view. Links open in the browser after their full address has been shown.
Patterns written by the user or carried in an import are matched under a
deadline.

## Where the checks end

- A developer whose signing key is stolen can sign anything. Stamp cannot see
  that. A minimum age for updates, which lets a bad release be pulled before it
  reaches you, is the setting that helps.
- A first install from a compromised release page installs what that page
  offers. Compare the fingerprint with one the developer publishes elsewhere.
- Stamp does not rebuild apps from source. It checks who signed a file, not
  what is in it.
- It has been tested on emulators. Vendor builds of Android can behave
  differently.
