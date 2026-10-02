# Tern on a television

An Android television has no file picker. The system answers a request for one with a screen that
only says no app can do this. Tern asks Android which of the two it has before it offers a picker,
and where there is none it exports and imports in the ways below. They work on a phone as well.

## Export

Tern writes the export into `Download/Tern/` on the shared storage and names it after the day,
`tern-apps-2026-09-29.json`. A second export on the same day is `tern-apps-2026-09-29-2.json`.
Tokens are never in it. Android lets an app put a file of its own there without any permission, so
Tern asks for none.

Export for Obtainium works the same way and writes a file Obtainium can import, named as Obtainium
names its own: `obtainium-export-2026-09-29.json`, then `obtainium-export-2026-09-29-2.json`.
What Obtainium has no setting for stays behind.

### Keep an export up to date

With Keep an export up to date on, Tern writes the export again a moment after anything in the
list changes. Without a file picker there is no folder to choose, so it goes into `Download/Tern/`
as well. It is always the same file, written over each time: `tern-apps.json`, or
`obtainium-export.json` in Obtainium's format, or the name you type under File name. Tern takes out
what a file name cannot hold and adds `.json` where it is missing.

### Save files

Save to Downloads on an app's page, and Save files to Downloads for several picked apps, put a copy
of a release's file into `Download/Tern/` as it came, under its own name. Nothing is checked or
installed.

## Import from a file

Tern lists every `.json` file it wrote itself into `Download/Tern/`, and every `.json` file in its
own folder on the shared storage, newest first, at most 50. That takes in its exports in either
format and the export it keeps up to date. A file of more than 2 MiB is refused. Android does not let
Tern see a file in `Download/` that another app or an earlier installation of Tern wrote, so a
file that comes from elsewhere has to go into Tern's own folder.

To put a file there, open the import screen in Tern once, which makes the folder, and then copy the
file into `Android/data/io.github.munzzyy.tern/files/import/` with a file manager that Android
allows to write there, or from a computer with adb:

    adb push tern-apps.json /sdcard/Android/data/io.github.munzzyy.tern/files/import/

The file then shows up in the list the next time the import screen opens. An export from Obtainium
is read the same way.

## Import from an address

Tern fetches an export from an address that starts with `https://`. It sends no token with that
request, follows a redirect only to another HTTPS address, and reads at most 2 MiB. The address has
to lead to the file itself, not to a page that shows it.

## Send from a phone

Choose Send from a phone, on the Add screen or among the ways to import. The television opens a
small web page on its own address in the local network for ten minutes and shows a QR code, the
address and a code of 20 letters and digits in five groups. Scan the QR code with the phone's
camera, or open the address in the phone's browser and type the code. Paste links or pick an
export file on the page that opens.

The code never travels over the network. The QR code carries it after the `#` of the address, which
a browser keeps to itself, and the page takes it out of the address bar at once. The phone's
browser seals what it sends with keys made from the code, and the television opens only what was
sealed with it, once. Someone else on the same network sees that a handoff took place and how many
bytes it carried, and nothing more. [SECURITY-MODEL.md](SECURITY-MODEL.md) says what that does
and does not protect against.

What arrives is listed on the television. A link opens the Add screen with its preview, and a file
opens the same summary as any import. Nothing is added until you have looked at it there. The page
closes when the ten minutes are over, when you leave the screen, or when Tern leaves the
foreground, and the screen says which of those it was.

## Allowing installs

Android asks once before one app may install another. On most televisions Tern opens the right
page of the settings for you. Where the television has no such page Tern can open, it says where
to look: on Android TV and Google TV under Settings, Apps, Security and restrictions, Unknown
sources, and on Fire TV under Settings, My Fire TV, Developer options, Install unknown apps. Choose
"I have done it" when you have, and what you asked for carries on.

Android 11 closes Tern the moment you turn the switch on. Android 12 does not: on its Android TV
emulator Tern stayed open and in front when the permission was given, and only taking it away
again closed Tern. Where Android does close it, Tern keeps what you wanted for 15 minutes, so open
it again and the install carries on by itself.
