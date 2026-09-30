# What Tern protects against

This page says what Tern checks, in what order, and where its checks end.
It is written so that someone can disagree with it point by point.

## Who is trusted

The developer of an app, as identified by the certificate their releases are
signed with. Tern's job is to make sure that what gets installed comes from
that certificate and is the file the developer published.

Not trusted: the network, a mirror, a repository index that is not signed, a
link someone sent you, an import file, and any text a server sends.

## The first install

Nothing on the device says who the developer is yet, so the first install is
trust on first use. Tern shows the certificate the file claims before you
install, as a fingerprint you can compare with one the developer publishes.
After Android has installed the file, that certificate is pinned.

A pin can also arrive with an import or a link. Such a pin is a decision somebody
else made, and the Add screen says so before anything is stored.

For fifteen apps of the starter list the first install is not taken on trust.
Tern carries the certificate they are signed with, and a first file signed by
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

Tern's parser and Android's package parser both read the file. If they
disagree on the package name or version code, the file is refused.

Both also verify the signature. Neither goes by the certificate a file names,
because naming a certificate costs nothing.

Android verifies a single file, and the base of a bundle, when Tern asks it
to read the file. A file Android will not read is refused. Every later
decision uses the certificates Android reported.

Tern verifies the same file with a verifier of its own, written from the
published specification of the APK signature schemes. It checks the signature
over the signed data, the digest of everything in the file outside the signing
block, that the public key is the key of the certificate shown, and every link
of a proof of rotation. It goes by the scheme Android goes by on the version
the device runs: the newest of v3.1, v3 and v2 that the file carries, and the
JAR signature where the file carries none of them. A scheme that does not hold
is never passed over for an older one. A signing block that was taken out of a
file is noticed, because the older signature says that it was there.

What the two come to:

- Tern finds that the signature does not hold, or that it holds for other
  certificates than Android reported: the file is refused.
- Tern cannot verify the file, and Android verified it: Android's answer
  stands.
- Android gave no reading of a part of a bundle. It gives none of a
  configuration part on Android 10, and none of a part with a broken signature
  on Android 16. Tern's verifier is then the only one: the part has to hold
  under it and has to be signed by the certificates of the base. A part that
  does not hold, and a part Tern cannot verify, both refuse the whole bundle.

Tern cannot verify, and says so instead of guessing:

- a signer whose strongest signature is over a verity digest (algorithms
  0x0421, 0x0423 and 0x0425)
- a signer with no algorithm from the specification of v2 and v3
- a file with a v3.2 block on Android 17 and later, the scheme with a second,
  post-quantum signature
- a rotation that is meant for a preview of the next Android, on the version
  the preview is built on
- a ZIP64 file
- a JAR signature of which the Java runtime takes no entry as signed. The Java
  of a computer does that to a signature made with SHA-1 and gives no reason.
  What the runtime of a device does with one has not been measured.

### The signer

The file must be signed by the signer of the installed app, and by the pinned
certificate if there is one. The certificates compared are the ones Android
reported after verifying the file. A rotated key is accepted where the file
proves descent from the known one.

In a bundle, every part must be signed by the signers of the base. A part
Android read is held to what Android reported for it. A part Android did not
read is held to what Tern's own verifier found, as described above. No part
passes on what it claims.

The log shows a file as verified after every file that goes to the installer
has been verified by Android or by Tern.

### Package, version and kind

A file for another package is refused. So is an older version code, a test-only
build, and a build for a newer Android than the device runs.

### Android

The system installer runs its own checks after all of that. Tern treats an
install as done when Android reports success and the package manager shows the
expected version code.

## Installers other than Android's

Every check above runs before any installer sees a file, whichever one the
person chose under Settings, Installing.

With Shizuku or root, Tern runs Android's own `pm install-create`,
`install-write` and `install-commit`, and streams the checked files into the
session. The command is made of fixed words, the package name the gate read and
checked, the sizes of the files and the session number `pm` gave back. Nothing
a server sent is ever part of it. Root runs it through `su -c` with every word
quoted. If the chosen installer is not there, Tern falls back to Android's own
and says so.

With another installer app, the checked file is copied, read-only, to a folder
of Tern's own and offered to that app alone through a content address. That app
may do anything with it, so the install counts only when the package manager
then shows the app signed by exactly the certificate the gate verified. Anything
else is reported and not counted.

## Stores and mirrors

Some sources are stores that offer again what developers published elsewhere,
and three are sites that offer apps someone else changed. The Add screen says so
before an app from one is stored. Their files are held to the same pin as any
other, so once the developer's certificate is known a file signed by someone
else is refused as an update.

Some stores hand out a link that expires, or a file only to a request with the
right headers. Such a link is asked for right before the download, has to be an
https address like any other, and has to point to one of the hosts that store
keeps its files on; each source names those hosts. The headers a source may add
never include `Authorization`, `Cookie`, `Proxy-Authorization`, `Host`, `Range`
or `If-Range`. A token still goes only to the host it was given for.

## Archives

A release that is a zip or tar archive, plain or compressed with gzip, bzip2
or xz, is opened in Tern's own staging folder. The bzip2 and xz readers are
Tern's own: every block, stream, index and footer is checked against its
checksum, what comes out is held to the same cap as a download, and an xz
dictionary may not ask for more than 64 MiB, what `xz -9` uses. zstd is
refused. The names of the files inside are data and never paths. Only the APKs
are taken, only those the app's filter for files inside archives lets through,
never more bytes than a download may hold and never more than 512 of them. They
then go through the same checks as the APKs of any bundle.

## Reading a file before downloading it

Tern reads a file's manifest and signing block from the server with range
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

With a GitHub token, a release's files are fetched through GitHub's API, which
serves them for a private project too; the token goes to `api.github.com` and
is dropped at the redirect to the file's host. A token GitHub refuses is tried
once without, so a public project is still followed. A GitLab token goes with
downloads from the same GitLab host, as a header and never in the address. A
search of a Forgejo or Gitea sends the token stored for exactly that host.

A hubproxy, set by hand for places where GitHub cannot be reached, sees every
request Tern makes to GitHub. It is never sent a token or a cookie, and while
one is set Tern holds back the tokens of GitHub's hosts altogether, so private
projects and GitHub Actions cannot be followed through it.

With a SOCKS proxy set, host names are resolved by the proxy. This was checked
with a logging proxy and a packet capture, and with a real Orbot on an emulator:
Tor Project's own check answered `{"IsTor":true}` for a request Tern sent.

A proxy that is set is never gone round. A request goes through it or fails,
also when the proxy does not answer and when the setting names no proxy at
all. One file opens connections, `net/UrlConnectionHttp.kt`, and
`tools/check-network-doors.sh` fails in CI when a second one appears, when
anything outside the handoff makes a socket, or when a name is resolved on the
device. Device tests send a check, a download and an icon through a proxy that
is not there and count the requests that arrive: none. When a request through a
proxy on the device fails, Tern asks the proxy whether it is there at all. If
not, the failure says "The proxy did not answer, so nothing was sent" instead of
what the socket said.

With the setting on Orbot, Tern finds out whether Orbot is connected by saying
hello to a SOCKS proxy on `127.0.0.1` at Orbot's port, the way SOCKS 5 begins,
and hanging up. Orbot took out the way for other apps to ask it on 2026-09-15.
An older Orbot is still sent the request and can answer with its status and
port. Any app on the device can send such an answer, so Tern takes nothing from
one but the status and a port on `127.0.0.1`. An answer can point Tern at
another port of the device. It cannot point it at another host and cannot make
a request go direct.

The limit of this: an app on the same device that listens on Orbot's port while
Orbot is off looks like Orbot to Tern, and Tern's requests then go to it. That
app sees which hosts Tern asks for, and the traffic stays HTTPS, so it can read
no more and change nothing without a certificate the device trusts. Every app
that uses Orbot's port shares this limit. Tern does not check who signed the
app that holds Orbot's package name.

### Which certificates are trusted

Android's own trust store decides, with certificate transparency asked for on
Android 16. Certificates a user or an administrator added are not trusted.

Two exceptions, both narrow:

- Pinning, off by default as in Obtainium. With it on, a connection to
  `github.com`, GitHub's two hosts for release files, `gitlab.com` or
  `codeberg.org` (and their subdomains) is taken only when the chain Android
  verified runs through the root each is known to use: Sectigo's R46 and E46 for
  GitHub and GitLab, and ISRG's X1, X2, YE and YR for GitHub and Codeberg.
  Android checks the chain first, as always; the pin adds a condition and never
  lifts one. The keys are in `net/Pinning.kt`, and `PinsTest` checks each one
  against the root certificate it names. A forge that moves to another
  authority fails there until the setting is off or Tern is updated.
- RuStore serves part of its addresses with a certificate of the Russian
  Trusted Root CA, which Android does not trust. That root is trusted for
  `rustore.ru` and its subdomains and for nothing else, as Obtainium does; its
  SHA-256 fingerprint is
  `D2:6D:2D:02:31:B7:C3:9F:92:CC:73:85:12:BA:54:10:35:19:E4:40:5D:68:B5:BD:70:3E:97:88:CA:8E:CF:31`.
  Certificates under it are in no transparency log, so Android 16 does not ask
  for one there. Whoever holds that root could read and change Tern's requests
  to RuStore, and only those. A file from RuStore still has to pass every
  check, including the certificate of the app itself.

## Notifications

A notification can be read on a locked screen. Every notification Tern posts
has a version without app names, which is what a lock screen set to hide
sensitive content shows. Android shows the full notification on a lock screen
that is not set that way, and that is how phones come, so Settings has a switch,
"Name the apps in notifications". Off, a notification says how many apps and
never which.

The Update and Update all buttons on a notification about updates start the
same install as the buttons in Tern, through every check above. So do the
widget's Update all button and the launcher shortcut of that name. The tile and
the widget's Check button only check.

## Links, shares and imports

A link or a shared text fills in the Add screen and nothing more. `obtainium://`
links are handled only after the user turns that on. An import file is checked
when read: known source types, web addresses, real package names, bounded
lengths. Filters and pins that came from outside are shown as such.

No file switches on updates that install by themselves. An app that arrives in
an import is stored as "tell me" even when the file asked for more, because a
file can come from anyone. That is for the user of the device to switch on,
app by app.

An export may carry settings, and an import may set them, from a list kept in
one place: the look of the list, notifications, when to check, and the defaults
for new apps. What reaches past the device or decides how it is protected is not
on that list, either way: tokens, the proxy, the hubproxy, the installer, the
folder of the kept export, the file filter for every app, and older versions
over newer ones. Obtainium's settings are read through the same list, under
Tern's names.

An import changes nothing that is already there until the person asks: apps
already in the list whose settings in the file differ, and any settings the
file carries, are offered, not taken. A replaced app keeps its certificate
pins, a package name it already knew, its repository's signing key and a
release the person skipped, and it never starts installing by itself. Taking a
file's settings never makes new apps install by themselves either.

A link that carries an app's settings is Obtainium's `obtainium://app/` form,
or the same link behind Obtainium's web page for opening it from a browser.
Tern makes such links without a token, which an app's settings never hold,
and with no request header but User-Agent, Accept, Accept-Language and
Referer, since any other could hold a key. Whoever opens the web form in a
browser shows the settings in it to Obtainium's page; Tern reads that form on
the device and never asks the page. A link of a kind Tern does not know is
named on the Add screen and goes no further.

## Text from servers

Names, authors, descriptions, versions, release titles, file names and the
names of repositories are cleaned where they enter, before they can reach a
screen or a notification: control and format characters, among them the marks
that turn the direction of writing and the characters of no width, are left
out, and white space is made one space. The same is done to the names in an
import file, to the label of an installed app and to the words of a failed
check. Without that, a name can be made to look like another.

Release notes are cleaned the same way, keeping their line breaks, and then
parsed into a small block model and drawn by the app. So are an app's notes, and
the project page, a README asked of the forge's API only when the person opens
it, with its HTML taken out first. There is no web view. Links open in the browser after their full address has been shown.
Patterns written by the user or carried in an import are matched under a
deadline.

## The handoff from a phone

A television has no file picker, and typing an address with a remote is slow.
So Tern can take links and one export file from a phone on the same network:
the device shows a QR code and a code of 20 letters and digits, the phone opens
a small page that the device itself serves, and what is sent there arrives on
the device. This is the only time Tern listens for connections. It does so
only after the user has opened the handoff, and everything Tern fetches stays
HTTPS only.

### What is open, and for how long

The page is served on the device's own private IPv4 address, on Wi-Fi or on a
network cable, on a port the system picks. It never listens on all addresses.
On a mobile network or behind a VPN the handoff does not open.

It closes after ten minutes, when the user closes it, after 200 connections,
and when Tern leaves the screen. The device says which of these it was.
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

The code is on the screen for anyone in the room, which is the point of it.
The screen does not forbid screenshots. The picture Android keeps of the last
screen for its list of recent apps is taken as Tern leaves the foreground, and
leaving the foreground closes the handoff, so the code in that picture no
longer opens anything.

### How a thing is sealed

The page makes three keys from the code, all with HMAC-SHA256. The master key
is worked out with the code as the key over the text `tern handoff v1`. The
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

Links and files wait in a list. Tern adds nothing by itself: what arrived is
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
- It cannot make Tern fetch anything. A link is text until the user adds it.
- It cannot stay open in the background, or past its ten minutes.
- It cannot be reached from outside the local network, unless that network
  passes connections from outside on to the device.

## Where the checks end

- A developer whose signing key is stolen can sign anything. Tern cannot see
  that. A minimum age for updates, which lets a bad release be pulled before it
  reaches you, is the setting that helps.
- A first install from a compromised release page installs what that page
  offers, unless Tern carries the app's certificate. Compare the fingerprint
  with one the developer publishes elsewhere.
- The certificates Tern carries were confirmed by F-Droid's signed index or
  by AppVerifier's list. AppVerifier's list is kept by people who look at the
  same release pages, at another time. It is no second channel.
- Tern does not rebuild apps from source. It checks who signed a file, not
  what is in it.
- A JAR signature, which old apps carry alone, covers what the entries of a
  file hold. It does not cover the zip structure around them, so Tern holds
  each entry against its own reading of the directory. It does not cover the
  certificate that comes with it either: a certificate changed in a place
  that is not its key still verifies, under another fingerprint. The pin
  refuses such a file as an update. On a first install the fingerprint shown
  is the one to compare.
- Tern's verifier has been tested on a computer, against files signed by
  apksigner and against files changed after signing. On a device it uses the
  cryptography and the JAR reader of that device. The tests for that have not
  been run yet.
- It has been tested on emulators. Vendor builds of Android can behave
  differently.
- The handoff has been tested with real connections on a computer, and its
  page in Chromium on a computer. No phone has loaded the page yet. How the
  handoff finds the device's address and how it closes when Tern leaves the
  screen has tests for a device that have not been run yet.
