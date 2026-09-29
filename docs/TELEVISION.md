# Stamp on a television

An Android television has no file picker. The system answers a request for one with a screen that
only says no app can do this. Stamp asks Android which of the two it has before it offers a picker,
and where there is none it exports and imports in the ways below. They work on a phone as well.

## Export

Stamp writes the export into `Download/Stamp/` on the shared storage and names it after the day,
`stamp-apps-2026-09-29.json`. A second export on the same day is `stamp-apps-2026-09-29-2.json`.
Tokens are never in it. Android lets an app put a file of its own there without any permission, so
Stamp asks for none.

## Import from a file

Stamp lists the exports it wrote itself, and every `.json` file in its own folder on the shared
storage, newest first, at most 50. A file of more than 2 MiB is refused. Android does not let
Stamp see a file in `Download/` that another app or an earlier installation of Stamp wrote, so a
file that comes from elsewhere has to go into Stamp's own folder.

To put a file there, open the import screen in Stamp once, which makes the folder, and then copy the
file into `Android/data/io.github.munzzyy.stamp/files/import/` with a file manager that Android
allows to write there, or from a computer with adb:

    adb push stamp-apps.json /sdcard/Android/data/io.github.munzzyy.stamp/files/import/

The file then shows up in the list the next time the import screen opens. An export from Obtainium
is read the same way.

## Import from an address

Stamp fetches an export from an address that starts with `https://`. It sends no token with that
request, follows a redirect only to another HTTPS address, and reads at most 2 MiB. The address has
to lead to the file itself, not to a page that shows it.
