# Notice: character dictionary

The files in this folder are a Kotlin port of **Japanese Character Name Dictionary** by Autumn (Bee),
https://github.com/bee-san/Japanese_Character_Name_Dictionary (MIT), `yomitan-dict-builder/src/`:

| Kotlin | Rust |
|---|---|
| `Kana.kt`, `RomajiTable.kt` | `kana.rs` |
| `NameParser.kt`, `Honorifics.kt` | `name_parser.rs` (name splitting, readings, the honorific suffix table) |
| `ContentBuilder.kt`, `Models.kt` | `content_builder.rs`, `models.rs` (structured-content card) |
| `YomitanCharacterZip.kt` | `dict_builder.rs` (entries, honorific variants, zip: index.json, tag_bank, term banks, img/) |
| `AniListCharacters.kt` | `anilist_client.rs` (characters query, 25 per page, 429 retry) |
| `ImageResizer.kt` | `image_handler.rs` |
| `CharacterDictionaryBuilder.kt` | the media loop of `main.rs` (`generate_dict_from_entries`) |

`AniListCharacters.resolve` / `franchise` and the bare-name / "From:" alias steps follow kumapie-server
(`services/feed/characters.py`, `tools/kumapie/reading_characters.py`), not Bee.

`Honorifics.kt`, `RomajiTable.kt` and `src/test/.../KanaCases.kt` are generated from the Rust sources.

This repo's `.gitignore` ignores `*.md`: this file needs `git add -f`.

## Licence of the original

MIT License

Copyright (c) 2026 Autumn (Bee)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
