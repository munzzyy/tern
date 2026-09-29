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

## The handoff from a phone

A television has no file picker, and typing an address with a remote is slow.
So Stamp can take links and one export file from a phone on the same network:
the device shows a QR code, the phone opens a small page that the device
itself serves, and what is sent there arrives on the device. This is the only
time Stamp listens for connections. It does so only after the user has opened
the handoff, and everything Stamp fetches stays HTTPS only.

### What is open, and for how long

The page is served on the device's own private IPv4 address, on Wi-Fi or on a
network cable, on a port the system picks. It never listens on all addresses.
On a mobile network or behind a VPN the handoff does not open.

It closes after ten minutes, when the user closes it, after five wrong PINs,
after 200 connections, and when Stamp leaves the screen. Closing frees the
port at once. Opening again makes a new secret, a new PIN and a new port.

There are two ways in. The QR code holds the address with a secret of 128
random bits in it, and that secret is never shown as text. For a phone that
cannot scan, the screen shows the bare address and a PIN of six random digits.
The page at the bare address asks for the PIN and sends the browser on to the
secret address. The secret and the PIN are compared in constant time.

### What the page can do

It can send up to 20 links of up to 2000 characters each, and one file of up to
2 MiB. Nothing else. The server knows five requests: the page, the two forms
on it, the page that asks for the PIN, and the PIN itself. Every other request
gets the same answer, whether the address is unknown, the secret is wrong, or
the request names another host. The last of these keeps out a web page that
points a name of its own at the device.

A request may have 8 KiB of headers and ten seconds to send them, and a body
has thirty seconds. A body is read only where one is expected and only when
the request says how long it is. Four connections are served at a time and the
others wait. The file has to arrive as the one part of the page's own form.
At most 40 things wait on the device, and at most 8 MiB of files.

The page is one document. It loads no picture, no font and no script from
anywhere, and its headers forbid the browser to. No answer holds anything the
phone sent.

### What arrives is a suggestion

Links and files wait in a list. Stamp adds nothing by itself: what arrived is
shown on the device, and nothing is added until the user has said yes there.
A link then goes the way of a link typed by hand, and a file the way of any
import. A file is not even read as a list of apps before the user chooses to
import it.

### What someone on the same network gains

The page is plain HTTP, because a device on a home network has no certificate
that a phone would trust.

- Someone who can read the traffic sees the secret, the links and the export
  file. Links and exports hold app addresses and certificate fingerprints.
  They never hold a token.
- Someone who can change the traffic, or who has read the secret, can change
  what arrives and send things of their own while the handoff is open. The
  user sees what arrived on the device before saying yes, and an import that
  brings pinned certificates or filters names them.
- Someone who guesses has five tries at the PIN, which is five in a million,
  and no chance at the secret.
- Anyone on the network can end a handoff early, with five wrong PINs or 200
  connections. Ending it is all they get.

### What the door cannot do

- It cannot add, install, update or remove an app, or change a setting.
- It cannot read anything. No answer holds the list of apps, a token, a
  setting or a file of the device.
- It cannot make Stamp fetch anything. A link is text until the user adds it.
- It cannot stay open in the background, or past its ten minutes.
- It cannot be reached from outside the local network, unless that network
  passes connections from outside on to the device.

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
- The handoff has been tested with real connections on a computer. How it
  finds the device's address and how it closes when Stamp leaves the screen
  has tests for a device that have not been run yet.
