#!/usr/bin/env python3
"""Генерация тестовых образцов книг: sample-en.epub и sample-en.pdf.

EPUB собирается вручную через zipfile (минимальный валидный контейнер).
PDF — через fpdf2, если установлен (pip install fpdf2); иначе собирается
минимальный PDF руками (один шрифт Helvetica, простые текстовые строки).
"""
import textwrap
import zipfile

TITLE = "The Little Journey"
PARAGRAPHS = [
    "The morning sun rose slowly over the quiet village. A young traveler stood at the edge of the forest, holding an old map in his hands. He had dreamed about this journey for many years, and now the moment had finally come.",
    "The path through the forest was narrow and dark. Tall trees covered the sky, and the air smelled of rain and earth. The traveler walked carefully, listening to the sounds of birds and the whisper of the wind.",
    "After several hours, he reached a small river. The water was clear and cold. An old wooden bridge crossed the river, and on the other side he could see a house with a red roof. Smoke rose from the chimney, and the smell of fresh bread filled the air.",
    'An old woman opened the door before he could knock. She smiled and said, "Welcome, traveler. We have been waiting for you." He did not understand her words, but her smile was warm, and he followed her inside.',
]


def make_epub(path: str) -> None:
    container = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""
    opf = """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="uid" version="2.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>The Little Journey</dc:title>
    <dc:language>en</dc:language>
    <dc:identifier id="uid">sample-en-epub</dc:identifier>
  </metadata>
  <manifest>
    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="ch1"/>
    <itemref idref="ch2"/>
  </spine>
</package>"""

    def chapter(title, paras):
        body = "\n".join(f"    <p>{p}</p>" for p in paras)
        return f"""<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>{title}</title></head>
<body>
  <h1>{title}</h1>
{body}
</body>
</html>"""

    with zipfile.ZipFile(path, "w") as z:
        # mimetype должен быть первым и без сжатия
        z.writestr(zipfile.ZipInfo("mimetype"), "application/epub+zip", zipfile.ZIP_STORED)
        z.writestr("META-INF/container.xml", container)
        z.writestr("OEBPS/content.opf", opf)
        z.writestr("OEBPS/ch1.xhtml", chapter(TITLE, PARAGRAPHS[:2]))
        z.writestr("OEBPS/ch2.xhtml", chapter("Chapter Two", PARAGRAPHS[2:]))
    print(f"OK: {path}")


def make_pdf_fpdf(path: str) -> bool:
    try:
        from fpdf import FPDF
    except ImportError:
        return False
    pdf = FPDF()
    pdf.set_auto_page_break(auto=True, margin=20)
    pdf.add_page()
    pdf.set_font("helvetica", "B", 16)
    pdf.cell(0, 12, TITLE, new_x="LMARGIN", new_y="NEXT")
    pdf.ln(6)
    pdf.set_font("helvetica", "", 12)
    for p in PARAGRAPHS:
        pdf.multi_cell(0, 7, p)
        pdf.ln(4)
    pdf.output(path)
    print(f"OK (fpdf2): {path}")
    return True


def pdf_escape(s: str) -> str:
    return s.replace("\\", r"\\").replace("(", r"\(").replace(")", r"\)")


def make_pdf_manual(path: str) -> None:
    """Минимальный PDF с текстовым слоем (WinAnsi/Helvetica, ASCII)."""
    lines = [TITLE, ""]
    for p in PARAGRAPHS:
        lines.extend(textwrap.wrap(p, 80))
        lines.append("")

    content_parts = ["BT", "/F1 11 Tf", "14 TL", "50 780 Td"]
    for i, line in enumerate(lines):
        if i:
            content_parts.append("T*")
        if line:
            content_parts.append(f"({pdf_escape(line)}) Tj")
    content_parts.append("ET")
    content = "\n".join(content_parts).encode("latin-1")

    objects = []
    objects.append(b"<< /Type /Catalog /Pages 2 0 R >>")
    objects.append(b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
    objects.append(b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] "
                   b"/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>")
    objects.append(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    objects.append(b"<< /Length %d >>\nstream\n" % len(content) + content + b"\nendstream")

    out = bytearray(b"%PDF-1.4\n")
    offsets = []
    for i, obj in enumerate(objects, start=1):
        offsets.append(len(out))
        out += b"%d 0 obj\n" % i + obj + b"\nendobj\n"
    xref_pos = len(out)
    out += b"xref\n0 %d\n" % (len(objects) + 1)
    out += b"0000000000 65535 f \n"
    for off in offsets:
        out += b"%010d 00000 n \n" % off
    out += (b"trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n"
            % (len(objects) + 1, xref_pos))
    with open(path, "wb") as f:
        f.write(bytes(out))
    print(f"OK (manual): {path}")


if __name__ == "__main__":
    make_epub("samples/sample-en.epub")
    if not make_pdf_fpdf("samples/sample-en.pdf"):
        make_pdf_manual("samples/sample-en.pdf")
