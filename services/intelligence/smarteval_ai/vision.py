import io
import math
import os
import re
import subprocess
import time
from shutil import which

from PIL import Image, ImageOps

MAX_PIXELS = 48_000_000
RENDER_PIXELS = 12_000_000
_CJK = re.compile(r"[\u3400-\u9fff\uf900-\ufaff\u3000-\u303f\uff00-\uffef]")


def tesseract_command():
    return os.environ.get("SMARTEVAL_TESSERACT") or which("tesseract")


def ocr_languages():
    return os.getenv("SMARTEVAL_OCR_LANGUAGES", "chi_sim+eng")


def ocr_readiness():
    command = tesseract_command()
    languages = ocr_languages().split("+")
    if not command:
        return {"ready": False, "reason": "OCR_ENGINE_UNAVAILABLE"}
    try:
        result = subprocess.run([command, "--list-langs"], capture_output=True, text=True,
                                encoding="utf-8", errors="replace", check=True, timeout=4)
    except (OSError, subprocess.SubprocessError):
        return {"ready": False, "reason": "OCR_ENGINE_UNAVAILABLE"}
    missing = set(languages).difference(line.strip() for line in result.stdout.splitlines())
    if missing:
        return {"ready": False, "reason": "OCR_LANGUAGE_MISSING", "missing_languages": sorted(missing)}
    return {"ready": True, "languages": languages}


def _has_large_image(page):
    page_area = page.rect.width * page.rect.height
    if page_area <= 0:
        return False
    for info in page.get_image_info():
        bbox = info.get("bbox")
        if not bbox or len(bbox) != 4:
            return True
        area = max(0.0, bbox[2] - bbox[0]) * max(0.0, bbox[3] - bbox[1])
        if area / page_area >= 0.15:
            return True
    return False


def extract(data: bytes, image_reader=None, deadline=float('inf')) -> list[dict]:
    import pymupdf

    deadline = min(deadline, time.monotonic() + 50)
    if data.startswith(b"%PDF-"):
        with pymupdf.open(stream=data, filetype="pdf") as document:
            if document.needs_pass or document.page_count > 20:
                raise ValueError("PDF is encrypted or has too many pages")
            pages = []
            for number, page in enumerate(document, 1):
                if time.monotonic() >= deadline:
                    pages.append({"page": number, "diagnostic": "PDF_PAGE_LIMIT", "blocks": []})
                    break
                if page.rect.width <= 0 or page.rect.height <= 0:
                    raise ValueError("PDF page has invalid dimensions")
                source = page.get_text("blocks")
                items = [{"text": b[4].strip(), "region": rectangle(tuple(pymupdf.Rect(b[:4]) * page.rotation_matrix),
                                                                    page.rect.width, page.rect.height)}
                         for b in source if b[6] == 0 and b[4].strip()]
                text_chars = sum(len(x["text"]) for x in items)
                prefer_pdf_text = bool(items) and (not image_reader or not _has_large_image(page) and text_chars >= 40
                                                   or not page.get_image_info())
                if prefer_pdf_text:
                    pages.append({"page": number, "text": "\n".join(x["text"] for x in items),
                                  "blocks": items, "origin": "PDF_TEXT", "confidence": None})
                else:
                    scale = min(2, math.sqrt(RENDER_PIXELS / (page.rect.width * page.rect.height)))
                    pixmap = page.get_pixmap(matrix=pymupdf.Matrix(scale, scale), alpha=False)
                    image = Image.frombytes("RGB", (pixmap.width, pixmap.height), pixmap.samples)
                    pages.append(image_page(image, number, image_reader, deadline, fallback_items=items or None))
            return pages
    with Image.open(io.BytesIO(data)) as source:
        if source.format not in {"JPEG", "PNG"} or source.width <= 0 or source.height <= 0 or source.width * source.height > MAX_PIXELS:
            raise ValueError("Image format or dimensions are unsupported")
        transposed = ImageOps.exif_transpose(source)
        transposed.thumbnail((3200, 3200))
        return [image_page(transposed, 1, image_reader, deadline)]


def image_page(image, number, reader, deadline=float('inf'), fallback_items=None):
    if reader:
        try:
            blocks = []
            for block in reader(image)[:150]:
                text = block["text"]
                region = block["region"]
                x, y, width, height = (float(region[key]) for key in ("x", "y", "width", "height"))
                if not isinstance(text,str) or not text.strip() or not all(math.isfinite(v) for v in (x,y,width,height)):
                    continue
                blocks.append({"text": text[:4000], "region": rectangle((x,y,x+width,y+height),1,1)})
            if blocks:
                return {"page":number, "blocks":blocks, "origin":"VISION", "diagnostic":"VERIFY_TRANSCRIPTION"}
        except Exception:
            pass
    if fallback_items:
        return {"page": number, "text": "\n".join(x["text"] for x in fallback_items),
                "blocks": fallback_items, "origin": "PDF_TEXT", "confidence": None}
    result = ocr(image, number, deadline)
    if reader and not result.get("diagnostic"):
        result["diagnostic"] = "VISION_FALLBACK_OCR"
    return result


def rectangle(coords, width, height):
    if width <= 0 or height <= 0:
        raise ValueError("Invalid region dimensions")
    x0, y0, x1, y1 = coords
    x0, x1 = max(0, x0 / width), min(1, x1 / width)
    y0, y1 = max(0, y0 / height), min(1, y1 / height)
    return {"x": x0, "y": y0, "width": max(0, x1 - x0), "height": max(0, y1 - y0)}


def page_preview(raw, number):
    if raw.startswith((b"\x89PNG\r\n\x1a\n", b"\xff\xd8\xff")):
        if number != 1:
            raise ValueError("Page unavailable")
        with Image.open(io.BytesIO(raw)) as source:
            if source.width <= 0 or source.height <= 0 or source.width * source.height > MAX_PIXELS:
                raise ValueError("Image dimensions are unsupported")
            image = ImageOps.exif_transpose(source).convert("RGB")
            image.thumbnail((1600, 1600))
            output = io.BytesIO()
            image.save(output, format="JPEG", quality=82)
            return output.getvalue()
    import pymupdf

    with pymupdf.open(stream=raw, filetype="pdf") as document:
        if document.needs_pass or not 1 <= number <= min(document.page_count,20):
            raise ValueError("Page unavailable")
        page = document[number-1]
        if page.rect.width <= 0 or page.rect.height <= 0:
            raise ValueError("Page unavailable")
        scale = min(2, math.sqrt(2_000_000 / (page.rect.width * page.rect.height)))
        image = page.get_pixmap(matrix=pymupdf.Matrix(scale, scale), alpha=False)
        return image.tobytes("jpeg", jpg_quality=82)


def _join_ocr_tokens(tokens):
    if not tokens:
        return ""
    parts = [tokens[0]]
    for token in tokens[1:]:
        prev = parts[-1]
        if _CJK.search(prev[-1]) and _CJK.search(token[0]):
            parts.append(token)
        else:
            parts.append(" " + token)
    return "".join(parts)


def ocr(image, number, deadline=float('inf')):
    import tempfile

    tesseract = tesseract_command()
    if not tesseract:
        return {"page": number, "text": "", "blocks": [], "origin": "OCR", "confidence": 0.0,
                "diagnostic": "OCR_ENGINE_UNAVAILABLE"}
    remaining = deadline - time.monotonic()
    if remaining <= 0.5:
        return {"page": number, "blocks": [], "origin": "OCR", "diagnostic": "OCR_FAILED"}
    normalized = ImageOps.autocontrast(image.convert("L"))
    original_size = normalized.size
    normalized.thumbnail((2400, 2400))
    with tempfile.TemporaryDirectory(prefix="smarteval-ocr-") as folder:
        file = os.path.join(folder, "input.png")
        normalized.save(file)
        try:
            response = subprocess.run(
                [tesseract, file, "stdout", "-l", ocr_languages(), "tsv"],
                capture_output=True, check=False, timeout=min(12, max(0.5, deadline - time.monotonic())),
            )
        except (OSError, subprocess.TimeoutExpired):
            return {"page":number, "blocks":[], "origin":"OCR", "diagnostic":"OCR_FAILED"}
    if response.returncode:
        return {"page": number, "text": "", "blocks": [], "origin": "OCR", "confidence": 0.0,
                "diagnostic": "OCR_FAILED"}
    words = []
    lines = {}
    for row in response.stdout.decode("utf-8", "replace").splitlines()[1:]:
        columns = row.split("\t", 11)
        if len(columns) != 12 or not columns[-1].strip():
            continue
        x, y, w, h = map(int, columns[6:10])
        confidence = float(columns[10])
        if confidence >= 0:
            words.append(confidence / 100)
            lines.setdefault(tuple(columns[1:5]), []).append((columns[-1].strip(), x, y, x+w, y+h))
    score = sum(words) / len(words) if words else 0.0
    blocks = []
    for line in lines.values():
        bounds = (min(w[1] for w in line), min(w[2] for w in line), max(w[3] for w in line), max(w[4] for w in line))
        blocks.append({"text": _join_ocr_tokens([w[0] for w in line]), "region":rectangle(bounds, *normalized.size)})
    result = {"page": number, "blocks": blocks, "origin": "OCR", "resolution": original_size, "confidence": score}
    if score < 0.8:
        result["diagnostic"] = "LOW_OCR_CONFIDENCE"
    return result
