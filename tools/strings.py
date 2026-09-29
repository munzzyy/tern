#!/usr/bin/env python3
"""Android string resources to JSON and back, and structural checks between a locale and English.

  tools/strings.py dump <res/values dir>                 -> JSON on stdout: {file: {name: text | {quantity: text}}}
  tools/strings.py write <res dir> <locale> <json file>  -> writes res/values-<locale>/<file>
  tools/strings.py check <res dir> [locale ...]          -> exit 1 on any structural problem
"""
import json, re, sys
import xml.etree.ElementTree as ET
from pathlib import Path

FILES = ["strings.xml", "strings_engine.xml"]
PLACEHOLDER = re.compile(r"%(?:(\d+)\$)?([sd])")
# CLDR plural categories per language, as Android uses them.
PLURALS = {
    "one_other": "bg ca da de el en eo es et eu fi gl hu it ml nb nl pt pt-rBR sv tr ur".split(),
    "other": "in id ja ko th vi zh-rCN zh-rTW ms".split(),
    "one_few_many_other": "ru uk pl".split(),
    "one_few_other": "cs sk bs hr sr ro".split(),
    "zero_one_two_few_many_other": "ar".split(),
    "one_two_many_other": "he iw".split(),
    "one_other_fa": "fa hi bn".split(),
    "one_many_other": "fr".split(),
}
CATEGORY_SETS = {
    "one_other": ["one", "other"], "other": ["other"], "one_few_many_other": ["one", "few", "many", "other"],
    "one_few_other": ["one", "few", "other"], "zero_one_two_few_many_other": ["zero", "one", "two", "few", "many", "other"],
    "one_two_many_other": ["one", "two", "many", "other"], "one_other_fa": ["one", "other"], "one_many_other": ["one", "many", "other"],
}


def categories(locale):
    for key, locales in PLURALS.items():
        if locale in locales:
            return CATEGORY_SETS[key]
    return ["one", "other"]


def unescape(text):
    text = text or ""
    out, i = [], 0
    while i < len(text):
        c = text[i]
        if c == "\\" and i + 1 < len(text):
            n = text[i + 1]
            out.append({"n": "\n", "t": "\t", "'": "'", '"': '"', "@": "@", "?": "?", "\\": "\\"}.get(n, n))
            i += 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def plain_dashes(text):
    return text.replace(" \u2014 ", " - ").replace(" \u2013 ", " - ").replace("\u2014", "-").replace("\u2013", "-")


def escape(text):
    text = plain_dashes(text)
    text = text.replace("\\", "\\\\").replace("'", "\\'").replace('"', '\\"').replace("\n", "\\n")
    if text[:1] in "@?":
        text = "\\" + text
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def read(path):
    out = {}
    root = ET.parse(path).getroot()
    for node in root:
        if node.get("translatable") == "false":
            continue
        if node.tag == "string":
            out[node.get("name")] = unescape("".join(node.itertext()))
        elif node.tag == "plurals":
            out[node.get("name")] = {item.get("quantity"): unescape("".join(item.itertext())) for item in node}
    return out


def dump(values):
    return {name: read(Path(values) / name) for name in FILES}


def write(res, locale, data):
    english = dump(Path(res) / "values")
    target = Path(res) / f"values-{locale}"
    target.mkdir(parents=True, exist_ok=True)
    for name in FILES:
        lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
        for key, source in english[name].items():
            if key == "app_name":
                continue
            value = data.get(name, {}).get(key)
            if value is None:
                continue
            if isinstance(source, dict):
                if not isinstance(value, dict):
                    continue
                lines.append(f'    <plurals name="{key}">')
                for quantity in categories(locale):
                    if quantity in value:
                        lines.append(f'        <item quantity="{quantity}">{escape(value[quantity])}</item>')
                lines.append("    </plurals>")
            elif isinstance(value, str):
                lines.append(f'    <string name="{key}">{escape(value)}</string>')
        lines.append("</resources>")
        (target / name).write_text("\n".join(lines) + "\n", encoding="utf-8")


def holders(text):
    found = sorted(m.group(0) for m in PLACEHOLDER.finditer(text))
    return found


def problems(res, locale):
    english = dump(Path(res) / "values")
    found = []
    for name in FILES:
        path = Path(res) / f"values-{locale}" / name
        if not path.exists():
            found.append(f"{locale}/{name}: missing file")
            continue
        try:
            local = read(path)
        except ET.ParseError as e:
            found.append(f"{locale}/{name}: not well-formed: {e}")
            continue
        for key in local:
            if key not in english[name]:
                found.append(f"{locale}/{name}: {key} does not exist in English")
        for key, source in english[name].items():
            if key == "app_name":
                if key in local:
                    found.append(f"{locale}/{name}: app_name must not be translated")
                continue
            if key not in local:
                # Left in English on purpose: Android falls back to the default string.
                continue
            value = local[key]
            if isinstance(source, dict) != isinstance(value, dict):
                found.append(f"{locale}/{name}: {key} changed kind")
                continue
            if isinstance(source, dict):
                wanted = categories(locale)
                if sorted(value) != sorted(wanted):
                    found.append(f"{locale}/{name}: {key} has quantities {sorted(value)}, wants {sorted(wanted)}")
                reference = holders(source["other"])
                for quantity, text in value.items():
                    got = holders(text)
                    # A language may spell "one" without the number; never may it add a placeholder.
                    if got != reference and not (quantity in ("one", "zero", "two") and set(got) <= set(reference)):
                        found.append(f"{locale}/{name}: {key}[{quantity}] placeholders {got}, English has {reference}")
                    if not text.strip():
                        found.append(f"{locale}/{name}: {key}[{quantity}] is empty")
            else:
                if holders(value) != holders(source):
                    found.append(f"{locale}/{name}: {key} placeholders {holders(value)}, English has {holders(source)}")
                if not value.strip():
                    found.append(f"{locale}/{name}: {key} is empty")
                if "\u2014" in value or "\u2013" in value:
                    found.append(f"{locale}/{name}: {key} contains a dash character")
                if len(source) <= 20 and len(value) > max(3 * len(source), 36):
                    found.append(f"{locale}/{name}: {key} is {len(value)} characters for a label of {len(source)}")
    return found


if __name__ == "__main__":
    command = sys.argv[1]
    if command == "dump":
        json.dump(dump(sys.argv[2]), sys.stdout, ensure_ascii=False, indent=1)
    elif command == "write":
        write(sys.argv[2], sys.argv[3], json.load(open(sys.argv[4], encoding="utf-8")))
    elif command == "check":
        res = sys.argv[2]
        locales = sys.argv[3:] or sorted(p.name[7:] for p in Path(res).glob("values-*") if (p / "strings.xml").exists())
        bad = [p for locale in locales for p in problems(res, locale)]
        print("\n".join(bad) if bad else f"ok   {len(locales)} locales match English")
        sys.exit(1 if bad else 0)
