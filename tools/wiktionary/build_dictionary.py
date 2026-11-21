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
имеет вид "м|ж|с|мн" (шёл|шла|шло|шли), для прилагательных —
"м|ж|с|мн[|ж.вин]" (старый|старая|старое|старые|старую) — род/число
выбирается на устройстве по существительному справа.
ru_gender(word, gender) — род (m/f/n) русских существительных
из переводов: согласование прилагательных и род подлежащего.
"""

import gzip
import json
import os
import re
import sqlite3
import sys
import time

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

# Ручные переводы для частых слов, где таблицы переводов английского
# Викисловаря неполны: у главного значения перевода часто нет вообще
# (woman → ничего, dog → «кобель», house → «палата», girl → «служанка»).
# (form, pos, tag, lemma) -> варианты; существующие строки заменяются,
# отсутствующие — добавляются. Парадигмы "м|ж|с|мн[|ж.вин]" для
# прилагательных и прош. вр. глаголов — как в основных данных.
MANUAL = {
    ("they", "pron", "plural", "they"): ["они"],
    ("them", "pron", "plural", "they"): ["их", "им"],
    ("their", "det", "", "their"): ["их"],
    ("beside", "prep", "", "beside"): ["рядом с", "возле", "помимо"],
    ("old", "adj", "", "old"): [
        "старый|старая|старое|старые|старую",
        "пожилой|пожилая|пожилое|пожилые|пожилую",
        "бывший|бывшая|бывшее|бывшие|бывшую"],
    # существительные
    ("woman", "noun", "", "woman"): ["женщина"],
    ("women", "noun", "plural", "woman"): ["женщины"],
    ("man", "noun", "", "man"): ["мужчина", "человек"],
    ("girl", "noun", "", "girl"): ["девочка", "девушка"],
    ("dog", "noun", "", "dog"): ["собака", "пёс"],
    ("house", "noun", "", "house"): ["дом", "здание"],
    ("mom", "noun", "", "mom"): ["мама"],
    ("mommy", "noun", "", "mom"): ["мамочка", "мама"],
    ("morning", "noun", "", "morning"): ["утро"],
    ("police", "noun", "", "police"): ["полиция"],
    ("week", "noun", "", "week"): ["неделя"],
    ("weeks", "noun", "plural", "week"): ["недели"],
    ("movie", "noun", "", "movie"): ["фильм"],
    ("movies", "noun", "plural", "movie"): ["фильмы"],
    ("sea", "noun", "", "sea"): ["море"],
    ("killer", "noun", "", "killer"): ["убийца"],
    ("killers", "noun", "plural", "killer"): ["убийцы"],
    ("cat", "noun", "", "cat"): ["кошка", "кот"],
    ("cats", "noun", "plural", "cat"): ["кошки", "коты"],
    ("wine", "noun", "", "wine"): ["вино"],
    ("kitchen", "noun", "", "kitchen"): ["кухня"],
    ("bathroom", "noun", "", "bathroom"): ["ванная", "туалет"],
    ("conversation", "noun", "", "conversation"): ["разговор"],
    ("conversations", "noun", "plural", "conversation"): ["разговоры"],
    ("photo", "noun", "", "photo"): ["фото", "фотография"],
    ("photos", "noun", "plural", "photo"): ["фото", "фотографии"],
    ("sheriff", "noun", "", "sheriff"): ["шериф"],
    ("lab", "noun", "", "lab"): ["лаборатория"],
    ("responsibility", "noun", "", "responsibility"): ["ответственность"],
    ("mayor", "noun", "", "mayor"): ["мэр"],
    ("style", "noun", "", "style"): ["стиль"],
    ("humans", "noun", "plural", "human"): ["люди"],
    ("council", "noun", "", "council"): ["совет"],
    ("honour", "noun", "", "honour"): ["честь"],
    ("destiny", "noun", "", "destiny"): ["судьба"],
    ("wheel", "noun", "", "wheel"): ["колесо"],
    ("wheels", "noun", "plural", "wheel"): ["колёса"],
    ("garbage", "noun", "", "garbage"): ["мусор"],
    ("tiger", "noun", "", "tiger"): ["тигр"],
    ("basement", "noun", "", "basement"): ["подвал"],
    ("bowl", "noun", "", "bowl"): ["миска", "чаша"],
    ("favour", "noun", "", "favour"): ["одолжение", "услуга"],
    ("neighbor", "noun", "", "neighbor"): ["сосед", "соседка"],
    ("neighbors", "noun", "plural", "neighbor"): ["соседи"],
    ("therapy", "noun", "", "therapy"): ["терапия"],
    ("odds", "noun", "plural", "odds"): ["шансы"],
    ("policeman", "noun", "", "policeman"): ["полицейский"],
    ("colour", "noun", "", "colour"): ["цвет"],
    ("theatre", "noun", "", "theatre"): ["театр"],
    ("consequences", "noun", "plural", "consequence"): ["последствия"],
    ("shark", "noun", "", "shark"): ["акула"],
    ("whiskey", "noun", "", "whiskey"): ["виски"],
    ("twin", "noun", "", "twin"): ["близнец"],
    ("twins", "noun", "plural", "twin"): ["близнецы"],
    ("locker", "noun", "", "locker"): ["шкафчик"],
    ("blonde", "noun", "", "blonde"): ["блондинка", "блондин"],
    ("cookie", "noun", "", "cookie"): ["печенье"],
    ("cookies", "noun", "plural", "cookie"): ["печенье"],
    ("fridge", "noun", "", "fridge"): ["холодильник"],
    ("cocaine", "noun", "", "cocaine"): ["кокаин"],
    ("cemetery", "noun", "", "cemetery"): ["кладбище"],
    ("aircraft", "noun", "", "aircraft"): ["самолёт"],
    ("embassy", "noun", "", "embassy"): ["посольство"],
    ("defence", "noun", "", "defence"): ["защита"],
    ("wolves", "noun", "plural", "wolf"): ["волки"],
    ("coal", "noun", "", "coal"): ["уголь"],
    ("cousin", "noun", "", "cousin"): ["двоюродный брат", "двоюродная сестра"],
    ("traveler", "noun", "", "traveler"): ["путешественник", "путешественница"],
    ("traveled", "verb", "past", "travel"): [
        "путешествовал|путешествовала|путешествовало|путешествовали"],
    ("sweetie", "noun", "", "sweetie"): ["милашка", "дорогая"],
    # прилагательные
    ("ready", "adj", "", "ready"): ["готовый|готова|готовое|готовые|готовую"],
    ("happy", "adj", "", "happy"): [
        "счастливый|счастливая|счастливое|счастливые|счастливую",
        "довольный|довольная|довольное|довольные|довольную"],
    ("happier", "adj", "comparative", "happy"): ["счастливее"],
    ("beautiful", "adj", "", "beautiful"): [
        "красивый|красивая|красивое|красивые|красивую",
        "прекрасный|прекрасная|прекрасное|прекрасные|прекрасную"],
    ("wonderful", "adj", "", "wonderful"): [
        "замечательный|замечательная|замечательное|замечательные|замечательную",
        "чудесный|чудесная|чудесное|чудесные|чудесную"],
    ("rich", "adj", "", "rich"): ["богатый|богатая|богатое|богатые|богатую"],
    ("favorite", "adj", "", "favorite"): ["любимый|любимая|любимое|любимые|любимую"],
    ("heavy", "adj", "", "heavy"): ["тяжёлый|тяжёлая|тяжёлое|тяжёлые|тяжёлую"],
    ("delicious", "adj", "", "delicious"): ["вкусный|вкусная|вкусное|вкусные|вкусную"],
    ("mobile", "adj", "", "mobile"): ["мобильный|мобильная|мобильное|мобильные|мобильную"],
    ("confident", "adj", "", "confident"): ["уверенный|уверенная|уверенное|уверенные|уверенную"],
    ("grey", "adj", "", "grey"): ["серый|серая|серое|серые|серую"],
    ("internal", "adj", "", "internal"): [
        "внутренний|внутренняя|внутреннее|внутренние|внутреннюю"],
    ("sunny", "adj", "", "sunny"): ["солнечный|солнечная|солнечное|солнечные|солнечную"],
    ("unfair", "adj", "", "unfair"): [
        "несправедливый|несправедливая|несправедливое|несправедливые|несправедливую"],
    ("vulnerable", "adj", "", "vulnerable"): ["уязвимый|уязвимая|уязвимое|уязвимые|уязвимую"],
    ("aggressive", "adj", "", "aggressive"): [
        "агрессивный|агрессивная|агрессивное|агрессивные|агрессивную"],
    ("rusty", "adj", "", "rusty"): ["ржавый|ржавая|ржавое|ржавые|ржавую"],
    ("slight", "adj", "", "slight"): [
        "небольшой|небольшая|небольшое|небольшие|небольшую",
        "лёгкий|лёгкая|лёгкое|лёгкие|лёгкую"],
    # глаголы
    ("shut", "verb", "", "shut"): ["закрыть", "захлопнуть"],
    ("die", "verb", "", "die"): ["умереть", "умирать"],
    ("died", "verb", "past", "die"): ["умер|умерла|умерло|умерли"],
    ("forgive", "verb", "", "forgive"): ["простить", "прощать"],
    ("create", "verb", "", "create"): ["создать", "создавать"],
    ("created", "verb", "past", "create"): ["создал|создала|создало|создали"],
    ("remove", "verb", "", "remove"): ["убрать", "удалить"],
    ("removed", "verb", "past", "remove"): ["убрал|убрала|убрало|убрали"],
    ("defend", "verb", "", "defend"): ["защитить", "защищать"],
    ("settle", "verb", "", "settle"): ["урегулировать", "поселиться"],
    ("realise", "verb", "", "realise"): ["понять", "осознать"],
    ("realised", "verb", "past", "realise"): [
        "понял|поняла|поняло|поняли", "осознал|осознала|осознало|осознали"],
    ("recognise", "verb", "", "recognise"): ["узнать", "признать"],
    ("wanna", "verb", "", "want"): ["хотеть"],
    ("gonna", "verb", "", "go"): ["собираться"],
    # наречия и прочее
    ("okay", "adv", "", "okay"): ["хорошо", "ладно"],
    ("alright", "adv", "", "alright"): ["хорошо", "ладно"],
    ("anymore", "adv", "", "anymore"): ["больше"],
    ("perhaps", "adv", "", "perhaps"): ["возможно", "пожалуй"],
    ("totally", "adv", "", "totally"): ["совершенно", "полностью"],
    ("possibly", "adv", "", "possibly"): ["возможно"],
    ("upon", "prep", "", "upon"): ["на", "по"],
    ("nor", "conj", "", "nor"): ["и не", "ни"],
    ("among", "prep", "", "among"): ["среди", "между"],
    ("towards", "prep", "", "towards"): ["к", "по направлению к"],
    ("lately", "adv", "", "lately"): ["недавно", "в последнее время"],
    ("recently", "adv", "", "recently"): ["недавно"],
    ("carefully", "adv", "", "carefully"): ["осторожно", "внимательно"],
    ("literally", "adv", "", "literally"): ["буквально"],
    ("particularly", "adv", "", "particularly"): ["особенно"],
    ("elsewhere", "adv", "", "elsewhere"): ["в другом месте"],
    ("nicely", "adv", "", "nicely"): ["хорошо", "мило"],
    ("backwards", "adv", "", "backwards"): ["назад", "в обратную сторону"],
    ("kinda", "adv", "", "kinda"): ["вроде", "типа"],
    ("bye", "intj", "", "bye"): ["пока"],
    ("goodnight", "intj", "", "goodnight"): ["спокойной ночи"],
    ("good-bye", "intj", "", "good-bye"): ["до свидания"],
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
    nouns = {}   # word -> {"plural":..., "gender": "m"/"f"/"n"}
    adjs = {}    # word -> {"comparative":..., "f":..., "n":..., "pl":..., "acc_f":...}
    GENDER_TAGS = {"masculine": "m", "feminine": "f", "neuter": "n"}
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
            if pos == "noun":
                # род существительного: из expansion шаблона заголовка
                # ("река • (reká) f inan ..."), запасной вариант — теги значений
                g = None
                for ht in d.get("head_templates", []):
                    m = re.search(r"\)\s*([mfn])\s+(?:in)?an(?:im)?\b", ht.get("expansion", ""))
                    if m:
                        g = m.group(1)
                        break
                if not g:
                    for s in d.get("senses", []):
                        g = next((GENDER_TAGS[t] for t in s.get("tags", [])
                                  if t in GENDER_TAGS), None)
                        if g:
                            break
                if g:
                    nouns.setdefault(word, {}).setdefault("gender", g)
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
                    elif tags == {"feminine", "nominative"}:
                        adjs.setdefault(word, {}).setdefault("f", fw)
                    elif tags == {"neuter", "nominative"}:
                        adjs.setdefault(word, {}).setdefault("n", fw)
                    elif tags == {"nominative", "plural"}:
                        adjs.setdefault(word, {}).setdefault("pl", fw)
                    elif tags == {"accusative", "feminine"}:
                        adjs.setdefault(word, {}).setdefault("acc_f", fw)
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
    # русские слова из ручных переводов (для рода и парадигм)
    for variants in MANUAL.values():
        for v in variants:
            ru_needed.add(v.split("|")[0])
    print(f"  русских слов в переводах: {len(ru_needed)}")

    print("проход 2: русский дамп...")
    verbs, nouns, adjs = pass_ru(ru_needed)
    print(f"  ru-морфология: глаголов {len(verbs)}, существительных {len(nouns)}, прилагательных {len(adjs)}")

    # сборка строк words
    rows = {}   # (form, pos) -> (lemma, tag, tr_json)

    def variants_json(key, tag):
        """JSON вариантов перевода с учётом грамматики формы."""
        lemma, pos = key
        out = []  # (приоритет, текст): для прош. вр. совершенный вид — первым
        for _, variants in lemmas[key]:
            sense_out = []
            for w, vtags in variants:
                prio = 0
                if pos == "verb" and w in verbs:
                    v = verbs[w]
                    if tag in ("past", "pastpart"):
                        pm, pf, pn, ppl = (v.get(k) for k in ("past_m", "past_f", "past_n", "past_pl"))
                        if pm and pf and pn:
                            prio = 0 if "perfective" in vtags else 1
                            sense_out.append((prio, f"{pm}|{pf}|{pn}|{ppl or pm}"))
                            continue
                    if tag == "pres3" and v.get("pres3"):
                        sense_out.append((prio, v["pres3"]))
                        continue
                    if tag == "gerund" and v.get("part_pres"):
                        sense_out.append((prio, v["part_pres"]))
                        continue
                    sense_out.append((prio, w))
                elif pos == "noun" and tag == "plural" and w in nouns and nouns[w].get("plural"):
                    sense_out.append((prio, nouns[w]["plural"]))
                elif pos == "adj":
                    if tag == "comparative" and w in adjs and adjs[w].get("comparative"):
                        sense_out.append((prio, adjs[w]["comparative"]))
                        continue
                    # парадигма "м|ж|с|мн[|ж.вин]" — согласование на устройстве
                    a = adjs.get(w)
                    if tag == "" and a and a.get("f") and a.get("n"):
                        paradigm = f"{w}|{a['f']}|{a['n']}|{a.get('pl') or w}"
                        if a.get("acc_f"):
                            paradigm += f"|{a['acc_f']}"
                        sense_out.append((prio, paradigm))
                    else:
                        sense_out.append((prio, w))
                else:
                    sense_out.append((prio, w))
            # совершенный вид первым — но только внутри своего значения,
            # чтобы редкие значения не выскакивали вперёд основных
            sense_out.sort(key=lambda x: x[0])
            out.extend(sense_out)
        # дедупликация с сохранением порядка
        seen, dedup = set(), []
        for _, o in out:
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

    # Ручные переводы (MANUAL): заменяют варианты существующих строк
    # и добавляют слова, которых в дампе нет вовсе
    for (form, pos, tag, lemma), variants in MANUAL.items():
        key = (form, pos)
        if key in rows:
            l, t, _ = rows[key]
            rows[key] = (l, t, json.dumps(variants, ensure_ascii=False))
        else:
            rows[key] = (lemma, tag, json.dumps(variants, ensure_ascii=False))

    # Переопределения по лемме и тегу формы: у частых слов первое значение
    # Викисловаря — не самое ходовое (walked: «шла», а не «ходила»)
    LEMMA_OVERRIDES = {
        ("walk", "verb"): {
            "": ["идти", "ходить", "гулять"],
            "past": ["шёл|шла|шло|шли", "ходил|ходила|ходило|ходили"],
            "pastpart": ["шёл|шла|шло|шли", "ходил|ходила|ходило|ходили"],
            "pres3": ["идёт", "ходит"],
        },
    }
    for (form, pos), (lemma, tag, _) in list(rows.items()):
        ov = LEMMA_OVERRIDES.get((lemma, pos))
        if ov and tag in ov:
            rows[(form, pos)] = (lemma, tag, json.dumps(ov[tag], ensure_ascii=False))

    # фразы: лемматизация слов фразы через построенную карту форм
    form2lemma = {}
    for (form, pos), (lemma, _, _) in rows.items():
        form2lemma.setdefault(form, lemma)

    # Спрягаем русский перевод фразы в прошедшем времени по родам:
    # первый глагол каждого сегмента перевода -> форма прош. вр.
    def conjugate_ru(ru_str, form_key):
        segs = re.split(r"([,;]\s*)", ru_str)
        out, changed = [], False
        for seg in segs:
            if re.fullmatch(r"[,;]\s*", seg or ""):
                out.append(seg)
                continue
            tokens = seg.split(" ")
            for i, tok in enumerate(tokens):
                clean = tok.strip("()«»\"'")
                v = verbs.get(clean)
                if v and v.get("past_m"):
                    pre = tok[:len(tok) - len(tok.lstrip("()«»\"'"))]
                    suf = tok[len(tok.rstrip("()«»\"'")):]
                    tokens[i] = pre + v.get(form_key, v["past_m"]) + suf
                    changed = True
                    break
            out.append(" ".join(tokens))
        return "".join(out) if changed else None

    # Ручной словарь идиом из приложения — в приоритете (идёт первым)
    phrase_rows = []
    seen_phrases = set()
    manual_path = os.path.join(os.path.dirname(__file__),
                               "..", "..", "app", "src", "main", "assets", "idioms.json")
    with open(manual_path, encoding="utf-8") as f:
        for obj in json.load(f):
            ws = re.findall(r"[A-Za-z]+(?:['’\-][A-Za-z]+)*", obj["en"].lower())
            if len(ws) < 2:
                continue
            lemmatized = " ".join(form2lemma.get(w, w) for w in ws)
            if lemmatized in seen_phrases:
                continue
            seen_phrases.add(lemmatized)
            phrase_rows.append((lemmatized, obj["ru"], obj["en"]))

    for ph, trs in phrases.items():
        ws = ph.split()
        lemmatized = " ".join(form2lemma.get(w, w) for w in ws)
        if lemmatized in seen_phrases:
            continue
        seen_phrases.add(lemmatized)
        variants = []
        for _, vv in trs:
            for w, _ in vv:
                if w not in variants:
                    variants.append(w)
        phrase_rows.append((lemmatized, "; ".join(variants[:MAX_TRANSLATIONS]), ph))

    # родовые варианты прош. вр. для фраз (м/ж/с/мн)
    phrase_rows_full = []
    for lemmatized, ru, src in phrase_rows:
        phrase_rows_full.append((
            lemmatized, ru, src,
            conjugate_ru(ru, "past_m"), conjugate_ru(ru, "past_f"),
            conjugate_ru(ru, "past_n"), conjugate_ru(ru, "past_pl"),
        ))

    # запись SQLite
    if os.path.exists(OUT):
        os.remove(OUT)
    con = sqlite3.connect(OUT)
    cur = con.cursor()
    # версия данных: приложение сравнивает user_version своей копии
    # с базой в assets — размер файла при обновлениях может совпасть
    cur.execute(f"PRAGMA user_version = {int(time.time())}")
    cur.executescript("""
        PRAGMA journal_mode = OFF;
        PRAGMA synchronous = OFF;
        CREATE TABLE words(form TEXT NOT NULL, pos TEXT NOT NULL,
                           lemma TEXT NOT NULL, tags TEXT NOT NULL,
                           tr TEXT NOT NULL, PRIMARY KEY(form, pos));
        CREATE INDEX idx_words_form ON words(form);
        CREATE TABLE phrases(lemmas TEXT NOT NULL, ru TEXT NOT NULL,
                             src TEXT NOT NULL,
                             ru_m TEXT, ru_f TEXT, ru_n TEXT, ru_pl TEXT);
        CREATE INDEX idx_phrases_lemmas ON phrases(lemmas);
        CREATE TABLE ru_gender(word TEXT PRIMARY KEY, gender TEXT NOT NULL);
    """)
    cur.executemany(
        "INSERT OR IGNORE INTO words(form, pos, lemma, tags, tr) VALUES (?,?,?,?,?)",
        [(f, p, l, t, tr) for (f, p), (l, t, tr) in rows.items()],
    )
    cur.executemany(
        "INSERT INTO phrases(lemmas, ru, src, ru_m, ru_f, ru_n, ru_pl) VALUES (?,?,?,?,?,?,?)",
        phrase_rows_full,
    )
    gender_rows = [(w, n["gender"]) for w, n in nouns.items() if n.get("gender")]
    # род и у форм мн. числа («The women walked» → женщины → ж.р.)
    gender_rows += [(n["plural"], n["gender"]) for n in nouns.values()
                    if n.get("gender") and n.get("plural")]
    cur.executemany(
        "INSERT OR IGNORE INTO ru_gender(word, gender) VALUES (?,?)",
        gender_rows,
    )
    con.commit()
    con.execute("VACUUM")
    con.close()

    gendered = sum(1 for r in phrase_rows_full if r[3])
    size = os.path.getsize(OUT) / 1e6
    print(f"готово: {OUT}, {size:.1f} МБ, слов {len(rows)}, фраз {len(phrase_rows_full)} "
          f"(с родами: {gendered}), ru_gender: {len(gender_rows)}")


if __name__ == "__main__":
    build()
