#!/usr/bin/env python3
"""Сборка компактного словаря en→ru для приложения из дампов kaikki.org
(машиночитаемая Викисловарь, CC BY-SA).

Вход:  kaikki-en.jsonl.gz  — английская Викисловарь (формы + переводы)
       kaikki-ru.jsonl.gz  — русская Викисловарь (склонения/спряжения)
       en_50k.txt          — частотный список (FrequencyWords, OpenSubtitles)
Выход: dictionary.db (SQLite):
       words(form, lemma, pos, tags, tr)   — поиск по тапнутому слову
       phrases(lemmas, ru, src)            — идиомы/устойчивые фразы (по леммам)

tags (компактные): past, pastpart, pres3, gerund, plural, comparative,
superlative, "" (базовая форма).
tr — JSON-массив вариантов перевода; для прош. вр. глаголов вариант
имеет вид "м|ж|с|мн" (шёл|шла|шло|шли) — род выбирается на устройстве.
"""

import gzip
import json
import re
import sqlite3
import sys
import unicodedata

EN_DUMP = "kaikki-en.jsonl.gz"
RU_DUMP = "kaikki-ru.jsonl.gz"
FREQ = "en_50k.txt"
OUT = "dictionary.db"

MAX_TRANSLATIONS = 3          # сколько вариантов перевода хранить на значение
MAX_SENSES = 2                # сколько значений (senses) учитывать на слово
PHRASE_MAX_WORDS = 8          # максимум слов во фразе-идиоме

STRESS = "́"  # U+0301
KEEP_EN = re.compile(r"^[a-z][a-z'\- ]*[a-z']$")

SKIP_FORM_TAGS = {
    "romanization", "table-tags", "class", "inflection-template",
    "canonical", "dated", "obsolete", "alternative", "nonstandard",
    "error-unrecognized-form", "archaic",
}


def strip_stress(w: str) -> str:
    return w.replace(STRESS, "")


def norm_en(w: str) -> str:
    return w.strip().lower()


def load_freq():
    freq = {}
    with open(FREQ, encoding="utf-8") as f:
        for line in f:
            parts = line.split()
            if len(parts) >= 2 and parts[1].isdigit():
                freq[parts[0].lower()] = int(parts[1])
    return freq


def ru_translations(entry, want_tags=None):
    """До MAX_SENSES значений, в каждом до MAX_TRANSLATIONS переводов на русский.
    Возвращает [(sense_gloss, [(word, tags), ...]), ...]."""
    out = []
    for s in entry.get("senses", []):
        trs = []
        for t in s.get("translations", []):
            if t.get("lang_code") != "ru":
                continue
            w = t.get("word")
            if not w:
                continue
            w = strip_stress(w)
            tags = [x for x in t.get("tags", []) if x in ("perfective", "imperfective")]
            if (w, tags) not in [(a, b) for a, b in trs]:
                trs.append((w, tags))
        if trs:
            gloss = (s.get("glosses") or [""])[0]
            out.append((gloss, trs[:MAX_TRANSLATIONS]))
        if len(out) >= MAX_SENSES:
            break
    return out


def en_form_tag(tags):
    """Сводит теги словоформы en к компактному виду."""
    t = set(tags)
    if t & SKIP_FORM_TAGS:
        return None
    if "past" in t and "participle" in t:
        return "pastpart"
    if "past" in t:
        return "past"
    if "plural" in t:
        return "plural"
    if "comparative" in t:
        return "comparative"
    if "superlative" in t:
        return "superlative"
    if "third-person" in t and "present" in t:
        return "pres3"
    if "participle" in t and "present" in t:
        return "gerund"
    return ""


# ---------- Проход 1: английский дамп ----------

def pass_en(freq):
    lemmas = {}     # (lemma, pos) -> ru_translations(...)
    en_forms = {}   # (lemma, pos) -> [(form, tag)]
    form_of = {}    # (form, pos) -> (lemma, tag)
    phrases = {}    # phrase -> [(gloss, [(ru, tags)])]

    n_lines = 0
    with gzip.open(EN_DUMP, "rt", encoding="utf-8") as f:
        for line in f:
            n_lines += 1
            if n_lines % 1_000_000 == 0:
                print(f"  en: {n_lines // 1_000_000}M строк...", flush=True)
            if '"lang_code": "en"' not in line:
                continue
            if '"translations"' not in line and '"form_of"' not in line:
                continue
            try:
                d = json.loads(line)
            except Exception:
                continue
            if d.get("lang_code") != "en":
                continue
            word = norm_en(d.get("word", ""))
            pos = d.get("pos", "")
            if not word or not KEEP_EN.match(word):
                continue
            multiword = " " in word

            trs = ru_translations(d)
            if multiword:
                # фраза-идиома: есть перевод и разумная длина
                if trs and 2 <= word.count(" ") + 1 <= PHRASE_MAX_WORDS:
                    phrases[word] = trs
                continue

            # form_of-записи (went -> go)
            is_form_of = False
            for s in d.get("senses", []):
                fo = s.get("form_of")
                if not fo:
                    continue
                tag = en_form_tag(s.get("tags", []))
                if tag is None:
                    continue
                lemma = norm_en(fo[0].get("word", ""))
                if lemma and lemma != word:
                    form_of.setdefault((word, pos), set()).add((lemma, tag))
                    is_form_of = True
            if is_form_of:
                continue

            # обычная лемма: берём все слова с переводами на русский
            forms = []
            for fo in d.get("forms", []):
                fw = norm_en(fo.get("form", ""))
                if not fw or fw == word or not KEEP_EN.match(fw) or " " in fw:
                    continue
                tag = en_form_tag(fo.get("tags", []))
                if tag is not None:
                    forms.append((fw, tag))
            if trs:
                key = (word, pos)
                if key not in lemmas:
                    lemmas[key] = trs
                if forms:
                    en_forms.setdefault(key, []).extend(forms)
    return lemmas, en_forms, form_of, phrases


# ---------- Проход 2: русский дамп ----------

def pass_ru(ru_needed):
    """ru_needed: множество русских слов (без ударений), встретившихся
    в переводах. Возвращает морфологию только для них."""
    verbs = {}   # word -> {"pres3":..., "past_m":..., "past_f":..., "past_n":..., "past_pl":...}
    nouns = {}   # word -> {"plural":...}
    adjs = {}    # word -> {"comparative":...}
    n = 0
    with gzip.open(RU_DUMP, "rt", encoding="utf-8") as f:
        for line in f:
            n += 1
            if n % 500_000 == 0:
                print(f"  ru: {n // 1000}K строк...", flush=True)
            if '"lang_code": "ru"' not in line:
                continue
            try:
                d = json.loads(line)
            except Exception:
                continue
            if d.get("lang_code") != "ru":
                continue
            word = strip_stress(d.get("word", ""))
            if word not in ru_needed:
                continue
            pos = d.get("pos", "")
            for fo in d.get("forms", []):
                fw = fo.get("form", "")
                if not fw or fw == "-" or " " in fw:
                    continue
                fw = strip_stress(fw)
                tags = set(fo.get("tags", []))
                if tags & SKIP_FORM_TAGS:
                    continue
                if pos == "verb":
                    v = verbs.setdefault(word, {})
                    if tags == {"past", "masculine", "singular"}:
                        v.setdefault("past_m", fw)
                    elif tags == {"past", "feminine", "singular"}:
                        v.setdefault("past_f", fw)
                    elif tags == {"past", "neuter", "singular"}:
                        v.setdefault("past_n", fw)
                    elif "past" in tags and "plural" in tags:
                        v.setdefault("past_pl", fw)
                    elif tags == {"present", "third-person", "singular"}:
                        v.setdefault("pres3", fw)
                    elif tags == {"participle", "active", "present"}:
                        v.setdefault("part_pres", fw)
                elif pos == "noun":
                    if tags == {"nominative", "plural"}:
                        nouns.setdefault(word, {})["plural"] = fw
                elif pos == "adj":
                    if "comparative" in tags:
                        adjs.setdefault(word, {})["comparative"] = fw
    return verbs, nouns, adjs


# ---------- Сборка ----------

def build():
    freq = load_freq()
    print(f"частотный список: {len(freq)} слов")

    print("проход 1: английский дамп...")
    lemmas, en_forms, form_of, phrases = pass_en(freq)
    print(f"  лемм с переводами: {len(lemmas)}, form_of: {len(form_of)}, фраз: {len(phrases)}")

    # какие русские слова нужны
    ru_needed = set()
    for trs in list(lemmas.values()) + list(phrases.values()):
        for _, variants in trs:
            for w, _ in variants:
                ru_needed.add(w)
    print(f"  русских слов в переводах: {len(ru_needed)}")

    print("проход 2: русский дамп...")
    verbs, nouns, adjs = pass_ru(ru_needed)
    print(f"  ru-морфология: глаголов {len(verbs)}, существительных {len(nouns)}, прилагательных {len(adjs)}")

    # сборка строк words
    rows = {}   # (form, pos) -> (lemma, tag, tr_json)

    def variants_json(key, tag):
        """JSON вариантов перевода с учётом грамматики формы."""
        lemma, pos = key
        out = []
        for _, variants in lemmas[key]:
            for w, vtags in variants:
                if pos == "verb" and w in verbs:
                    v = verbs[w]
                    if tag in ("past", "pastpart"):
                        pm, pf, pn, ppl = (v.get(k) for k in ("past_m", "past_f", "past_n", "past_pl"))
                        if pm and pf and pn:
                            out.append(f"{pm}|{pf}|{pn}|{ppl or pm}")
                            continue
                    if tag == "pres3" and v.get("pres3"):
                        out.append(v["pres3"])
                        continue
                    if tag == "gerund" and v.get("part_pres"):
                        out.append(v["part_pres"])
                        continue
                    out.append(w)
                elif pos == "noun" and tag == "plural" and w in nouns and nouns[w].get("plural"):
                    out.append(nouns[w]["plural"])
                elif pos == "adj" and tag == "comparative" and w in adjs and adjs[w].get("comparative"):
                    out.append(adjs[w]["comparative"])
                else:
                    out.append(w)
        # дедупликация с сохранением порядка
        seen, dedup = set(), []
        for o in out:
            if o not in seen:
                seen.add(o)
                dedup.append(o)
        return json.dumps(dedup[:MAX_TRANSLATIONS * 2], ensure_ascii=False)

    lemma_words = {l for (l, _) in lemmas}

    def accept_form(form, lemma):
        """Словоформа, которая сама является леммой ("opened", "book"),
        принимается как чужая форма только если её лемма заметно
        частотнее (open > opened — ок; bake < book — мусор)."""
        if form not in lemma_words:
            return True
        return freq.get(lemma, 0) > freq.get(form, 0)

    for key in lemmas:
        lemma, pos = key
        rows[(lemma, pos)] = (lemma, "", variants_json(key, ""))
        seen_tags = {}
        for fw, tag in en_forms.get(key, []):
            if not accept_form(fw, lemma):
                continue
            fk = (fw, pos)
            if fk in seen_tags or fk in rows:
                continue
            seen_tags[fk] = tag
            rows[fk] = (lemma, tag, variants_json(key, tag))
    # form, которая сама является леммой, — только если лемма-источник
    # частотнее (went→go — ок; book→bake — мусор)
    for (form, pos), targets in form_of.items():
        for lemma, tag in sorted(targets):
            if not accept_form(form, lemma):
                continue
            key = (lemma, pos)
            if key not in lemmas:
                continue
            fk = (form, pos)
            if fk in rows:
                continue
            rows[fk] = (lemma, tag, variants_json(key, tag))
            break

    print(f"  строк words: {len(rows)}")

    # фразы: лемматизация слов фразы через построенную карту форм
    form2lemma = {}
    for (form, pos), (lemma, _, _) in rows.items():
        form2lemma.setdefault(form, lemma)

    phrase_rows = []
    for ph, trs in phrases.items():
        ws = ph.split()
        lemmatized = " ".join(form2lemma.get(w, w) for w in ws)
        variants = []
        for _, vv in trs:
            for w, _ in vv:
                if w not in variants:
                    variants.append(w)
        phrase_rows.append((lemmatized, "; ".join(variants[:MAX_TRANSLATIONS]), ph))

    # запись SQLite
    import os
    if os.path.exists(OUT):
        os.remove(OUT)
    con = sqlite3.connect(OUT)
    cur = con.cursor()
    cur.executescript("""
        PRAGMA journal_mode = OFF;
        PRAGMA synchronous = OFF;
        CREATE TABLE words(form TEXT NOT NULL, pos TEXT NOT NULL,
                           lemma TEXT NOT NULL, tags TEXT NOT NULL,
                           tr TEXT NOT NULL, PRIMARY KEY(form, pos));
        CREATE INDEX idx_words_form ON words(form);
        CREATE TABLE phrases(lemmas TEXT NOT NULL, ru TEXT NOT NULL,
                             src TEXT NOT NULL);
        CREATE INDEX idx_phrases_lemmas ON phrases(lemmas);
    """)
    cur.executemany(
        "INSERT OR IGNORE INTO words(form, pos, lemma, tags, tr) VALUES (?,?,?,?,?)",
        [(f, p, l, t, tr) for (f, p), (l, t, tr) in rows.items()],
    )
    cur.executemany("INSERT INTO phrases(lemmas, ru, src) VALUES (?,?,?)", phrase_rows)
    con.commit()
    con.execute("VACUUM")
    con.close()

    import os
    size = os.path.getsize(OUT) / 1e6
    print(f"готово: {OUT}, {size:.1f} МБ, слов {len(rows)}, фраз {len(phrase_rows)}")


if __name__ == "__main__":
    build()
