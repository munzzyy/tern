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

For fifteen apps of the starter list the first install is not taken on trust.
Stamp carries the certificate they are signed with, and a first file signed by
anyone else is refused. A certificate is carried only when a second place names
the same one as the developer's own file. `docs/SUGGESTIONS.md` has the list,
the second place for each, and the ten entries for which none was found. A pin
that arrives with a link or an import does not take the place of a carried one.

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

A proxy that is set is never gone round. A request goes through it or fails,
also when the proxy does not answer and when the setting names no proxy at
all. One file opens connections, `net/UrlConnectionHttp.kt`, and
`tools/check-network-doors.sh` fails in CI when a second one appears, when
anything outside the handoff makes a socket, or when a name is resolved on the
device. Device tests for this exist and have not been run yet.

With the setting on Orbot, Stamp asks Orbot how it is doing and uses the port
Orbot reports. Any app on the device can send such an answer, so Stamp takes
nothing from one but the status and a port on `127.0.0.1`. An answer can point
Stamp at another port of the device. It cannot point it at another host and
cannot make a request go direct. Stamp does not check who signed the app that
holds Orbot's package name.

## Links, shares and imports

A link or a shared text fills in the Add screen and nothing more. `obtainium://`
links are handled only after the user turns that on. An import file is checked
when read: known source types, web addresses, real package names, bounded
lengths. Filters and pins that came from outside are shown as such.

No file switches on updates that install by themselves. An app that arrives in
an import is stored as "tell me" even when the file asked for more, because a
file can come from anyone. That is for the user of the device to switch on,
app by app.

## Text from servers

Names, authors, descriptions, versions, release titles, file names and the
names of repositories are cleaned where they enter, before they can reach a
screen or a notification: control and format characters, among them the marks
that turn the direction of writing and the characters of no width, are left
out, and white space is made one space. The same is done to the names in an
import file, to the label of an installed app and to the words of a failed
check. Without that, a name can be made to look like another.

Release notes are cleaned the same way, keeping their line breaks, and then
parsed into a small block model and drawn by the app. There is
no web view. Links open in the browser after their full address has been shown.
Patterns written by the user or carried in an import are matched under a
deadline.

## The handoff from a phone

A television has no file picker, and typing an address with a remote is slow.
So Stamp can take links and one export file from a phone on the same network:
the device shows a QR code and a code of 20 letters and digits, the phone opens
a small page that the device itself serves, and what is sent there arrives on
the device. This is the only time Stamp listens for connections. It does so
only after the user has opened the handoff, and everything Stamp fetches stays
HTTPS only.

### What is open, and for how long

The page is served on the device's own private IPv4 address, on Wi-Fi or on a
network cable, on a port the system picks. It never listens on all addresses.
On a mobile network or behind a VPN the handoff does not open.

It closes after ten minutes, when the user closes it, after 200 connections,
and when Stamp leaves the screen. The device says which of these it was.
Closing frees the port at once. Opening again makes a new code and a new port.

### The code

The page is plain HTTP, because a device on a home network has no certificate
that a phone would trust. What keeps the content between the two devices is
the code.

Each handoff makes 100 random bits and writes them as 20 characters, shown in
five groups of four. The alphabet is the one of base32: the letters and the
digits 2 to 7. It has no 0, 1, 8 or 9. The code is never sent over the network
in any form: not as it is, not hashed, and not as part of an address that is
asked for.

There are two ways to give it to the page, and they end in the same place.

- The QR code holds the address of the page with the code behind a number
  sign. A browser keeps that part of an address to itself and asks the device
  for the page alone. The page reads the code from there and takes it out of
  the address bar at once.
- For a phone that cannot scan, the screen shows the bare address, and the
  page asks for the code. It takes capital and small letters, with spaces and
  dashes anywhere. A character that the alphabet does not have is refused in a
  sentence. The page never guesses what was meant.

### How a thing is sealed

The page makes three keys from the code, all with HMAC-SHA256. The master key
is worked out with the code as the key over the text `stamp handoff v1`. The
key that encrypts is worked out with the master key over `enc`, and the key
that signs with the master key over `mac`.

The content is encrypted with ChaCha20 as RFC 8439 describes it, under a nonce
of 12 random bytes and from block 1. Then the tag is worked out with
HMAC-SHA256 over one byte that says whether these are links or a file, the
nonce and the encrypted content. These four travel together, written in
base64, in the one field of one request.

The device does the following, in this order.

1. It checks the length of the request against the largest file there can be.
2. It works out the tag and compares it in constant time. Nothing has been
   decrypted at this point.
3. It refuses a nonce that it has opened before in this handoff.
4. It decrypts, and what comes out meets the limits below.

Whatever is wrong with a thing that does not open, the answer is the same
sentence, to the byte. A thing that does not open does not count towards
closing the handoff. The 200 connections are the only budget.

A browser gives a page that came over plain HTTP no `crypto.subtle`, so the
page carries SHA-256, HMAC and ChaCha20 written out in its script, in 32 bit
arithmetic, where anyone can read them in the source of the page. The random
bytes come from `crypto.getRandomValues`. On the device HMAC is the one of
the platform. ChaCha20 is written out there as well, because Android
lets an app start its own ChaCha20 at a block of its choosing only from
API 35. The tests hold both against the vectors of RFC 8439 and RFC 4231,
against OpenSSL, and against each other, and the one on the device against
the cipher of the JDK.

### What the page can do

It can send up to 20 links of up to 2000 characters each, and one file of up to
2 MiB. Nothing else. The server knows two requests: the page, and a sealed
thing that is sent to it. Every other request gets the same answer, whether
the address is unknown or the request names another host. The last of these
keeps out a web page that points a name of its own at the device.

A request may have 8 KiB of headers and ten seconds to send them, and a body
has thirty seconds. A body is read only where one is expected and only when
the request says how long it is. Four connections are served at a time and the
others wait. At most 40 things wait on the device, and at most 8 MiB of files.

The page is one document. It loads no picture, no font and no script from
anywhere. Its Content-Security-Policy names the SHA-256 of its one script and
of its one style, which are the same for every handoff, and allows nothing
else: no other script, no style in an attribute, no form that is submitted,
and no request except to the device itself.

With scripts turned off the page says that it needs them and offers nothing
to send, because without its script it could only send things unsealed. No
field of the page has a name, so no browser can send what was typed into it
as a plain form. No answer holds anything the phone sent.

### What arrives is a suggestion

Links and files wait in a list. Stamp adds nothing by itself: what arrived is
shown on the device, and nothing is added until the user has said yes there.
A link then goes the way of a link typed by hand, and a file the way of any
import. A file is not even read as a list of apps before the user chooses to
import it.

### What this gives, and what it does not

- Someone who only listens learns that a handoff took place, when, whether
  links or a file were sent, how many bytes, and what the device answered.
  For a file the number of bytes says roughly how long the list of apps is.
  They cannot read what was sent, cannot send anything the device accepts,
  and cannot send a recorded message a second time.
- Someone who can change traffic on the network, and not only read it, can
  replace the page with one of their own and get the code from whoever types
  or scans it. They can also change what the device answers. Nothing that a
  page on a local address can do prevents that, because the page itself
  arrives over plain HTTP. What they then send is still only a suggestion: it
  is shown on the device, and nothing is added until the person there has
  looked at it and said yes. An import that brings pinned certificates or
  filters names them.
- Someone who guesses needs no answer from the device. One recorded message
  is enough to try codes against, as fast as their machines work. The code
  has 100 bits so that it can still be typed. Guessing it takes up to 2 to
  the power of 100 tries, each of them a few runs of HMAC-SHA256, and that
  is out of reach.
- Someone who has recorded a handoff and learns its code later, from a
  photograph of the screen for one, can read what was sent in it. The keys
  come from the code alone. The code is shown only while the handoff is open,
  and the next handoff has another.
- Someone who can see the screen can read the code, and the app that scans
  the QR code reads it too. The code keeps out the network, not the room.
- Anyone on the network can end a handoff early with 200 connections. Ending
  it is all they get.

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
  offers, unless Stamp carries the app's certificate. Compare the fingerprint
  with one the developer publishes elsewhere.
- The certificates Stamp carries were confirmed by F-Droid's signed index or
  by AppVerifier's list. AppVerifier's list is kept by people who look at the
  same release pages, at another time. It is no second channel.
- Stamp does not rebuild apps from source. It checks who signed a file, not
  what is in it.
- It has been tested on emulators. Vendor builds of Android can behave
  differently.
- The handoff has been tested with real connections on a computer, and its
  page in Chromium on a computer. No phone has loaded the page yet. How the
  handoff finds the device's address and how it closes when Stamp leaves the
  screen has tests for a device that have not been run yet.
