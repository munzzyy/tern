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
- Updates without a prompt where Android allows it, and says so where it does not.
- Background checks through Android's job scheduler.
- Imports Obtainium's export file, and adds apps from a GitHub user's stars.
- Several apps can be selected at once to file under a category, check,
  update or remove.
- A project that moved can be followed to its new address when the package and
  the signer are the same there.
- Phone, tablet and keyboard or D-pad layouts, light, dark and black themes.
