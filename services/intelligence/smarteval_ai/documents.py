import io
import os
import posixpath
import subprocess
import tempfile
import time
from pathlib import Path
from xml.etree import ElementTree
from zipfile import ZipFile

from .vision import extract

WORD = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
LINK = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}"
MATH_TEXT = "{http://schemas.openxmlformats.org/officeDocument/2006/math}t"


def xml(data):
    if b"<!DOCTYPE" in data.upper() or b"<!ENTITY" in data.upper() or b"\x00" in data:
        raise ValueError("Unsupported Word XML")
    return ElementTree.fromstring(data)


DOCX_MAX_TOTAL_BYTES = 64 * 1024 * 1024
DOCX_MAX_MEMBER_BYTES = 16 * 1024 * 1024


def _read_member(archive, name, budget):
    cap = min(DOCX_MAX_MEMBER_BYTES, budget[0])
    if cap <= 0:
        raise ValueError("Word document exceeds decompression limits")
    chunks = []
    read = 0
    with archive.open(name, "r") as stream:
        while True:
            chunk = stream.read(min(65536, cap - read + 1))
            if not chunk:
                break
            read += len(chunk)
            if read > cap:
                raise ValueError("Word document exceeds decompression limits")
            chunks.append(chunk)
    budget[0] -= read
    return b"".join(chunks)


def word_blocks(raw, image_reader=None, deadline=float('inf')):
    with ZipFile(io.BytesIO(raw)) as archive:
        entries = archive.infolist()
        if len(entries) > 2048 or sum(entry.file_size for entry in entries) > DOCX_MAX_TOTAL_BYTES:
            raise ValueError("Word document exceeds decompression limits")
        budget = [DOCX_MAX_TOTAL_BYTES]
        doc = xml(_read_member(archive, "word/document.xml", budget))
        relations = {}
        names = set(archive.namelist())
        path = "word/_rels/document.xml.rels"
        if path in names:
            for item in xml(_read_member(archive, path, budget)):
                raw_target = item.get("Target")
                if item.get("TargetMode") != "External" and raw_target:
                    if raw_target.startswith("/"):
                        target = posixpath.normpath(raw_target.lstrip("/"))
                    else:
                        target = posixpath.normpath(posixpath.join("word", raw_target))
                    if target.startswith("word/media/") and target in names:
                        relations[item.get("Id")] = target
        body = doc.find(WORD+"body")
        if body is None:
            raise ValueError("Word document has no body")
        output = []
        images = 0
        deadline = min(deadline, time.monotonic() + 50)
        table_locations = {}
        for index, table in enumerate(body.iter(WORD+"tbl"),1):
            for row, tr in enumerate(table.findall(WORD+"tr"),1):
                for column, cell in enumerate(tr.findall(WORD+"tc"),1):
                    for paragraph in cell.iter(WORD+"p"):
                        table_locations[paragraph] = {"table":index,"row":row,"column":column}
        for index, paragraph in enumerate(body.iter(WORD+"p"), 1):
            if index > 1500 or time.monotonic() >= deadline:
                output.append({"paragraph":index, "text":"", "diagnostic":"WORD_PARSE_LIMIT"})
                break
            location = {"paragraph":index, **table_locations.get(paragraph,{})}
            words = []
            for part in paragraph.iter():
                if part.tag in {WORD+"t", MATH_TEXT} and part.text:
                    words.append(part.text)
                elif part.tag == WORD+"br":
                    words.append("\n")
                elif part.tag == WORD+"tab":
                    words.append("\t")
            text = "".join(words).strip()
            if text:
                output.append({**location, "text":text, "origin":"WORD"})
            for element in paragraph.iter():
                target = relations.get(element.get(LINK+"embed"))
                if not target:
                    continue
                if not target.lower().endswith((".png", ".jpg", ".jpeg")):
                    output.append({**location, "text":"", "diagnostic":"WORD_IMAGE_UNSUPPORTED"})
                    continue
                if images >= 16 or time.monotonic() >= deadline:
                    output.append({**location, "text":"", "diagnostic":"WORD_PARSE_LIMIT"})
                    break
                images += 1
                try:
                    for page in extract(_read_member(archive, target, budget), image_reader, deadline):
                        output.extend(page_blocks(page, **location))
                except (ValueError, OSError, RuntimeError):
                    output.append({**location, "text":"", "diagnostic":"WORD_IMAGE_UNSUPPORTED"})
        return output


def docx_from_doc(raw, deadline):
    remaining = deadline - time.monotonic()
    if remaining <= 1:
        raise TimeoutError("Document conversion deadline exhausted")
    with tempfile.TemporaryDirectory(prefix="smarteval-doc-") as folder:
        root = Path(folder)
        source = root / "input.doc"
        source.write_bytes(raw)
        profile = (root / "profile").as_uri()
        config = root / "profile" / "user"
        config.mkdir(parents=True)
        (config / "registrymodifications.xcu").write_text(
            '<?xml version="1.0"?><oor:items xmlns:oor="http://openoffice.org/2001/registry">'
            '<item oor:path="/org.openoffice.Office.Common/Security/Scripting">'
            '<prop oor:name="MacroSecurityLevel" oor:op="fuse"><value>3</value></prop>'
            '</item><item oor:path="/org.openoffice.Office.Writer/Content/Update">'
            '<prop oor:name="Link" oor:op="fuse"><value>0</value></prop>'
            '</item></oor:items>',
            encoding="utf-8",
        )
        subprocess.run([os.getenv("SMARTEVAL_SOFFICE", "soffice"), f"-env:UserInstallation={profile}",
                        "--headless", "--norestore", "--convert-to", "docx", "--outdir", folder, source],
                       check=True, timeout=min(35, max(1.0, deadline - time.monotonic())), capture_output=True)
        result = root / "input.docx"
        if result.stat().st_size > 24 * 1024 * 1024:
            raise ValueError("Converted document exceeds size limit")
        return result.read_bytes()


def page_blocks(page, **location):
    where = {"page": page["page"], **location}
    if page.get("diagnostic"):
        yield {**where, "text": "", "diagnostic": page["diagnostic"]}
    for block in page.get("blocks", []):
        yield {**where, **block, "origin": page["origin"]}


def blocks(raw, file_format, image_reader=None, deadline=float('inf')):
    if file_format in {"pdf", "jpg", "png"}:
        return [block for page in extract(raw, image_reader, deadline) for block in page_blocks(page)]
    if file_format == "doc":
        return word_blocks(docx_from_doc(raw, deadline), image_reader, deadline)
    if file_format == "docx":
        return word_blocks(raw, image_reader, deadline)
    if file_format in {"txt", "py", "java", "cpp", "c", "js"}:
        return [{"text": raw.decode("utf-8-sig"), "line": 1, "origin": "CODE" if file_format != "txt" else "TEXT"}]
    raise ValueError("Unsupported material format")
